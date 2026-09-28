package au.edu.oshc.smartguide;

import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/profile")
class ProfileController {
    final UserRepository users; final BCryptPasswordEncoder enc; final MfaService mfa; final ProgressRepository progress; final TokenService tokenService; final AuthCookies cookies;
    ProfileController(UserRepository u,BCryptPasswordEncoder e,MfaService m,ProgressRepository pr,TokenService ts,AuthCookies c){users=u;enc=e;mfa=m;progress=pr;tokenService=ts;cookies=c;}

    User current(Authentication auth){if(auth==null||!auth.isAuthenticated())return null;try{return users.findById(Long.valueOf(auth.getName())).orElse(null);}catch(Exception e){return null;}}
    ResponseEntity<?> unauthorized(){return ResponseEntity.status(401).body(Map.of("message","Authentication required."));}

    Map<String,Object> view(User u){
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("fullName",Objects.toString(u.getFullName(),""));
        out.put("userId",String.format("OSHC-%06d",u.getId()));
        out.put("address",Objects.toString(u.getAddress(),""));
        out.put("birthdate",Objects.toString(u.getBirthdate(),""));
        out.put("phoneNumber",Objects.toString(u.getPhoneNumber(),""));
        out.put("email",u.getEmail()); out.put("photoData",Objects.toString(u.getPhotoData(),""));
        out.put("mfaEnabled",u.isMfaEnabled()); out.put("role",Objects.toString(u.getRole(),"STUDENT"));
        return out;
    }

    @GetMapping ResponseEntity<?> get(Authentication auth){User u=current(auth);return u==null?unauthorized():ResponseEntity.ok(view(u));}

    @PutMapping ResponseEntity<?> update(@RequestBody Map<String,Object>b,Authentication auth){
        User u=current(auth);if(u==null)return unauthorized();
        u.setFullName(clean(Objects.toString(b.get("fullName"),""),100));
        u.setAddress(clean(Objects.toString(b.get("address"),""),300));
        u.setBirthdate(clean(Objects.toString(b.get("birthdate"),""),20));
        u.setPhoneNumber(clean(Objects.toString(b.get("phoneNumber"),""),40));
        if(b.containsKey("photoData")){String photo=Objects.toString(b.get("photoData"),"");if(photo.length()>3_000_000)return ResponseEntity.badRequest().body(Map.of("message","Photo is too large. Please use an image under about 2 MB."));u.setPhotoData(photo);}
        users.save(u);return ResponseEntity.ok(view(u));
    }

    @PostMapping("/email") ResponseEntity<?> email(@RequestBody Map<String,String>b,Authentication auth){
        User u=current(auth);if(u==null)return unauthorized();
        if(!enc.matches(b.getOrDefault("currentPassword",""),u.getPasswordHash()))return ResponseEntity.badRequest().body(Map.of("message","Current password is incorrect."));
        String email=b.getOrDefault("newEmail","").trim().toLowerCase();
        if(!email.matches(AuthController.EMAIL_REGEX))return ResponseEntity.badRequest().body(Map.of("message","Enter a valid email address."));
        if(users.findByEmail(email).filter(x->!x.getId().equals(u.getId())).isPresent())return ResponseEntity.status(409).body(Map.of("message","That email is already registered."));
        u.setEmail(email);users.save(u);return ResponseEntity.ok(view(u));
    }

    @PostMapping("/password") ResponseEntity<?> password(@RequestBody Map<String,String>b,Authentication auth,HttpServletResponse response){
        User u=current(auth);if(u==null)return unauthorized();
        if(!enc.matches(b.getOrDefault("currentPassword",""),u.getPasswordHash()))return ResponseEntity.badRequest().body(Map.of("message","Current password is incorrect."));
        String np=b.getOrDefault("newPassword","");
        if(!AuthController.strongPassword(np))return ResponseEntity.badRequest().body(Map.of("message",AuthController.PASSWORD_MESSAGE));
        u.setPasswordHash(enc.encode(np));users.save(u);tokenService.revokeAllForUser(u.getId());cookies.clearAll(response);
        return ResponseEntity.ok(Map.of("message","Password changed successfully. Please sign in again."));
    }

    @PostMapping("/mfa/enable") ResponseEntity<?> enableMfa(@RequestBody Map<String,String>b,Authentication auth,HttpSession session){
        User u=current(auth);if(u==null)return unauthorized();
        if(!enc.matches(b.getOrDefault("password",""),u.getPasswordHash()))return ResponseEntity.badRequest().body(Map.of("message","Password verification failed."));
        String secret=mfa.secret();session.setAttribute("MFA_ENABLE_SECRET",secret);session.setAttribute("MFA_ENABLE_USER_ID",u.getId());session.setAttribute("MFA_ENABLE_STARTED_AT",System.currentTimeMillis());session.setMaxInactiveInterval(5*60);
        return ResponseEntity.ok(Map.of("otpauthUri",mfa.uri(u.getEmail(),secret),"mfaEnabled",u.isMfaEnabled()));
    }

    @PostMapping("/mfa/enable/verify") ResponseEntity<?> verifyEnableMfa(@RequestBody Map<String,String>b,Authentication auth,HttpSession session){
        User u=current(auth);if(u==null)return unauthorized();
        String secret=(String)session.getAttribute("MFA_ENABLE_SECRET");Long id=(Long)session.getAttribute("MFA_ENABLE_USER_ID");Long started=(Long)session.getAttribute("MFA_ENABLE_STARTED_AT");
        if(secret==null||id==null||started==null||!id.equals(u.getId())||System.currentTimeMillis()-started>5*60*1000L)return ResponseEntity.badRequest().body(Map.of("message","MFA setup has expired. Start setup again."));
        if(!mfa.valid(secret,b.getOrDefault("code","")))return ResponseEntity.badRequest().body(Map.of("message","Invalid authenticator code."));
        u.setMfaSecret(secret);u.setMfaEnabled(true);users.save(u);session.removeAttribute("MFA_ENABLE_SECRET");session.removeAttribute("MFA_ENABLE_USER_ID");session.removeAttribute("MFA_ENABLE_STARTED_AT");
        return ResponseEntity.ok(Map.of("message","MFA enabled successfully.","mfaEnabled",true));
    }

    @PostMapping("/mfa/disable") ResponseEntity<?> disableMfa(@RequestBody Map<String,String>b,Authentication auth,HttpServletResponse response){
        User u=current(auth);if(u==null)return unauthorized();
        if(!enc.matches(b.getOrDefault("password",""),u.getPasswordHash()))return ResponseEntity.badRequest().body(Map.of("message","Password verification failed."));
        u.setMfaEnabled(false);users.save(u);tokenService.revokeAllForUser(u.getId());cookies.clearAll(response);
        return ResponseEntity.ok(Map.of("message","MFA disabled. You must sign in again."));
    }

    @PostMapping("/mfa/reset") ResponseEntity<?> resetMfa(@RequestBody Map<String,String>b,Authentication auth,HttpSession session){
        User u=current(auth);if(u==null)return unauthorized();
        if(!enc.matches(b.getOrDefault("password",""),u.getPasswordHash()))return ResponseEntity.badRequest().body(Map.of("message","Password verification failed."));
        String secret=mfa.secret();u.setMfaSecret(secret);u.setMfaEnabled(false);users.save(u);
        session.setAttribute("PENDING",u.getEmail());session.setMaxInactiveInterval(5*60);
        return ResponseEntity.ok(Map.of("otpauthUri",mfa.uri(u.getEmail(),secret),"message","MFA reset. Complete authenticator verification to enable it again."));
    }

    @DeleteMapping ResponseEntity<?> deleteAccount(@RequestBody Map<String,String>b,Authentication auth,HttpServletResponse response){
        User u=current(auth);if(u==null)return unauthorized();
        if(!enc.matches(b.getOrDefault("password",""),u.getPasswordHash()))return ResponseEntity.badRequest().body(Map.of("message","Password verification failed."));
        String email=u.getEmail();progress.findByEmail(email).ifPresent(progress::delete);tokenService.revokeAllForUser(u.getId());users.delete(u);cookies.clearAll(response);
        return ResponseEntity.ok(Map.of("message","Account deleted successfully."));
    }

    String clean(String v,int max){String x=v==null?"":v.trim();return x.substring(0,Math.min(x.length(),max));}
}
