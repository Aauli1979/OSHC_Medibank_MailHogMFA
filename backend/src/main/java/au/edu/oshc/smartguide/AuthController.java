package au.edu.oshc.smartguide;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
class AuthController {
    static final String PASSWORD_MESSAGE = "Password requirements at least 12 characters (UPPERCASE, lowercase, a number, special characters eg ?=.*[@$!%*?&]).";
    static final String EMAIL_REGEX = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$";

    final UserRepository users;
    final BCryptPasswordEncoder enc;
    final MfaService mfa;
    final PasswordResetTokenRepository resetTokens;
    final PasswordResetService resetService;
    final ProgressRepository progress;
    final TokenService tokenService;
    final AuthCookies cookies;

    AuthController(UserRepository users, BCryptPasswordEncoder enc, MfaService mfa,
                   PasswordResetTokenRepository resetTokens, PasswordResetService resetService,
                   ProgressRepository progress, TokenService tokenService, AuthCookies cookies) {
        this.users=users; this.enc=enc; this.mfa=mfa; this.resetTokens=resetTokens;
        this.resetService=resetService; this.progress=progress; this.tokenService=tokenService; this.cookies=cookies;
    }

    @PostMapping("/register")
    ResponseEntity<?> register(@RequestBody Map<String,String> b, HttpSession session, HttpServletResponse response) {
        String email=b.getOrDefault("email","").trim().toLowerCase();
        String password=b.getOrDefault("password","");
        if(!email.matches(EMAIL_REGEX)) return ResponseEntity.badRequest().body(Map.of("message","Enter a valid email address."));
        if(!strongPassword(password)) return ResponseEntity.badRequest().body(Map.of("message",PASSWORD_MESSAGE));
        if(users.findByEmail(email).isPresent()) return ResponseEntity.status(409).body(Map.of("message","Account already exists."));

        User u=new User();
        u.setEmail(email);
        u.setPasswordHash(enc.encode(password));
        u.setMfaSecret(mfa.secret());
        u.setMfaEnabled(false);
        u.setRegistrationMfaPending(true);
        u.setFullName("OSHC Student");
        u.setRole("STUDENT");
        users.save(u);

        // MFA is mandatory during account creation. The account is not
        // authenticated until the student scans the QR code and verifies
        // the six-digit TOTP code.
        session.setAttribute("REGISTER_MFA", email);
        session.setMaxInactiveInterval(10 * 60);
        session.removeAttribute("PENDING");
        return ResponseEntity.ok(Map.of(
                "authenticated", false,
                "mfaRequired", true,
                "registrationMfaRequired", true,
                "email", email,
                "otpauthUri", mfa.uri(email, u.getMfaSecret()),
                "message", "Account created. Complete MFA registration before entering the application."
        ));
    }

    @PostMapping("/login")
    ResponseEntity<?> login(@RequestBody Map<String,String> b, HttpSession session, HttpServletResponse response) {
        String email=b.getOrDefault("email","").trim().toLowerCase();
        String password=b.getOrDefault("password","");
        User u=users.findByEmail(email).orElse(null);
        if(u==null || !enc.matches(password,u.getPasswordHash())) return ResponseEntity.status(401).body(Map.of("message","Invalid credentials."));

        if(u.isRegistrationMfaPending()) {
            session.setAttribute("REGISTER_MFA", email);
            session.setMaxInactiveInterval(10 * 60);
            return ResponseEntity.ok(Map.of(
                    "authenticated", false,
                    "mfaRequired", true,
                    "registrationMfaRequired", true,
                    "email", email,
                    "otpauthUri", mfa.uri(email, u.getMfaSecret()),
                    "message", "Complete MFA registration before entering the application."
            ));
        }

        if(u.isMfaEnabled()) {
            session.setAttribute("PENDING",email);
            session.setMaxInactiveInterval(5*60);
            return ResponseEntity.ok(Map.of("authenticated",false,"mfaRequired",true));
        }

        session.removeAttribute("PENDING");
        issueTokens(u,response);
        return ResponseEntity.ok(Map.of("authenticated",true,"mfaRequired",false,"email",email,"role",role(u)));
    }

    @PostMapping("/register/mfa/verify")
    ResponseEntity<?> verifyRegistrationMfa(@RequestBody Map<String,String> b, HttpSession session, HttpServletResponse response) {
        String email=(String)session.getAttribute("REGISTER_MFA");
        if(email==null) return ResponseEntity.status(401).body(Map.of("message","Your MFA registration session has expired. Please create the account again or sign in again."));
        User u=users.findByEmail(email).orElse(null);
        if(u==null || !u.isRegistrationMfaPending()) return ResponseEntity.status(401).body(Map.of("message","MFA registration is not pending for this account."));
        if(!mfa.valid(u.getMfaSecret(),b.getOrDefault("code",""))) return ResponseEntity.status(401).body(Map.of("message","Invalid authenticator code."));

        u.setMfaEnabled(true);
        u.setRegistrationMfaPending(false);
        users.save(u);
        session.removeAttribute("REGISTER_MFA");
        issueTokens(u,response);
        return ResponseEntity.ok(Map.of(
                "authenticated",true,
                "mfaRequired",false,
                "registrationMfaRequired",false,
                "email",u.getEmail(),
                "role",role(u),
                "message","MFA registration completed. Your account is now protected by MFA."
        ));
    }

    @PostMapping("/mfa/verify")
    ResponseEntity<?> verify(@RequestBody Map<String,String> b, HttpSession session, HttpServletResponse response) {
        String email=(String)session.getAttribute("PENDING");
        if(email==null) return ResponseEntity.status(401).body(Map.of("message","No pending authentication. Please sign in again."));
        User u=users.findByEmail(email).orElse(null);
        if(u==null) return ResponseEntity.status(401).body(Map.of("message","No pending authentication. Please sign in again."));
        if(!mfa.valid(u.getMfaSecret(),b.getOrDefault("code",""))) return ResponseEntity.status(401).body(Map.of("message","Invalid authenticator code."));

        u.setMfaEnabled(true);
        users.save(u);
        session.removeAttribute("PENDING");
        issueTokens(u,response);
        return ResponseEntity.ok(Map.of("authenticated",true,"email",u.getEmail(),"role",role(u)));
    }

    @PostMapping("/refresh")
    ResponseEntity<?> refresh(HttpServletResponse response, @CookieValue(value="SG_REFRESH",required=false) String refresh) {
        User u=tokenService.userFromRefreshToken(refresh);
        if(u==null) { cookies.clearAll(response); return ResponseEntity.status(401).body(Map.of("message","Refresh token is invalid or expired.")); }
        tokenService.revoke(refresh,TokenService.REFRESH);
        issueTokens(u,response);
        return ResponseEntity.ok(Map.of("authenticated",true,"email",u.getEmail(),"role",role(u)));
    }

    @GetMapping("/me")
    ResponseEntity<?> me(org.springframework.security.core.Authentication auth) {
        User u=current(auth);
        if(u==null) return ResponseEntity.status(401).body(Map.of("authenticated",false));
        return ResponseEntity.ok(Map.of("authenticated",true,"email",u.getEmail(),"role",role(u),
                "fullName",safe(u.getFullName()),"mfaEnabled",u.isMfaEnabled()));
    }

    @PostMapping("/forgot-password")
    ResponseEntity<?> forgotPassword(@RequestBody Map<String,String> b) {
        String email=b.getOrDefault("email","").trim().toLowerCase();
        if(email.matches(EMAIL_REGEX)) {
            User u=users.findByEmail(email).orElse(null);
            if(u!=null) {
                try { String token=resetService.createToken(email); resetService.send(email,token); }
                catch(IllegalStateException ex) { return ResponseEntity.status(503).body(Map.of("message",ex.getMessage())); }
            }
        }
        return ResponseEntity.ok(Map.of("message","If an account exists for that email, a password reset link has been sent."));
    }

    @PostMapping("/reset-password")
    ResponseEntity<?> resetPassword(@RequestBody Map<String,String> b) {
        String token=b.getOrDefault("token","");
        String password=b.getOrDefault("newPassword","");
        if(!strongPassword(password)) return ResponseEntity.badRequest().body(Map.of("message",PASSWORD_MESSAGE));
        PasswordResetToken t=resetTokens.findByToken(token).orElse(null);
        if(t==null || t.getExpiresAt()<=System.currentTimeMillis()) return ResponseEntity.badRequest().body(Map.of("message","This password reset link is invalid or has expired."));
        User u=users.findByEmail(t.getEmail()).orElse(null);
        if(u==null) return ResponseEntity.badRequest().body(Map.of("message","This password reset link is invalid or has expired."));
        u.setPasswordHash(enc.encode(password));
        users.save(u);
        tokenService.revokeAllForUser(u.getId());
        resetTokens.delete(t);
        return ResponseEntity.ok(Map.of("message","Password reset successfully. You can now sign in with your new password."));
    }

    @PostMapping("/revoke-all")
    ResponseEntity<?> revokeAll(org.springframework.security.core.Authentication auth, HttpServletResponse response) {
        User u=current(auth);
        if(u==null) return ResponseEntity.status(401).body(Map.of("message","Authentication required."));
        tokenService.revokeAllForUser(u.getId());
        cookies.clearAll(response);
        return ResponseEntity.ok(Map.of("message","All active sessions have been revoked."));
    }

    @PostMapping("/logout")
    ResponseEntity<?> logout(@CookieValue(value="SG_ACCESS",required=false) String access,
                             @CookieValue(value="SG_REFRESH",required=false) String refresh,
                             HttpServletResponse response) {
        tokenService.revoke(access,TokenService.ACCESS);
        tokenService.revoke(refresh,TokenService.REFRESH);
        cookies.clearAll(response);
        return ResponseEntity.noContent().build();
    }

    void issueTokens(User u,HttpServletResponse response) {
        String access=tokenService.createAccessToken(u);
        String refresh=tokenService.createRefreshToken(u);
        cookies.issue(response,access,refresh);
    }

    User current(org.springframework.security.core.Authentication auth) {
        if(auth==null || !auth.isAuthenticated()) return null;
        try { return users.findById(Long.valueOf(auth.getName())).orElse(null); }
        catch(NumberFormatException e){ return null; }
    }
    static String role(User u){return u.getRole()==null||u.getRole().isBlank()?"STUDENT":u.getRole();}
    static String safe(String s){return s==null?"":s;}
    static boolean strongPassword(String p){
        return p!=null && p.length()>=12 &&
                p.matches(".*[A-Z].*") && p.matches(".*[a-z].*") &&
                p.matches(".*[0-9].*") && p.matches(".*[@$!%*?&].*");
    }
}
