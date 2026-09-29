package au.edu.oshc.smartguide;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
class PasskeyController {
    private final PasskeyService passkeys;
    private final PasskeyChallenges challenges;
    private final UserRepository users;
    private final ObjectMapper json;
    private final PasskeyRateLimiter limiter;
    private final AuthenticationFlow authentication;

    PasskeyController(PasskeyService passkeys, PasskeyChallenges challenges, UserRepository users,
                      ObjectMapper json, PasskeyRateLimiter limiter, AuthenticationFlow authentication) {
        this.passkeys = passkeys; this.challenges = challenges; this.users = users;
        this.json = json; this.limiter = limiter; this.authentication = authentication;
    }
    record RegistrationStart(String name, String currentPassword, String code) {}
    record Reauthentication(String currentPassword, String code) {}
    record Rename(String name) {}

    private User current(Authentication auth) {
        if (auth != null && auth.isAuthenticated()) {
            try {
                User user = users.findById(Long.valueOf(auth.getName())).orElse(null);
                if (user != null && !user.isRegistrationMfaPending()) return user;
            } catch (NumberFormatException ignored) { }
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in first.");
    }

    @GetMapping("/csrf")
    Map<String, String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
    }

    @PostMapping("/passkeys/registration/options")
    JsonNode registrationOptions(@RequestBody RegistrationStart body, HttpServletRequest request, Authentication auth) throws Exception {
        limiter.check(request.getRemoteAddr());
        HttpSession session = request.getSession();
        User user = current(auth);
        var options = passkeys.startRegistration(user.id, body.name(), body.currentPassword(), body.code());
        challenges.put(session, "register", options.toJson(), user.id, body.name());
        return json.readTree(options.toCredentialsCreateJson());
    }

    @PostMapping("/passkeys/registration/verify")
    PasskeyService.View registrationVerify(@RequestBody String response, HttpServletRequest request, Authentication auth) {
        limiter.check(request.getRemoteAddr());
        HttpSession session = request.getSession();
        User user = current(auth);
        var challenge = challenges.consume(session, "register", user.id);
        if (response.length() > 65_536) throw PasskeyService.invalid();
        return passkeys.finishRegistration(user.id, challenge.name(), challenge.optionsJson(), response);
    }

    @PostMapping("/passkeys/authentication/options")
    JsonNode authenticationOptions(HttpServletRequest request) throws Exception {
        limiter.check(request.getRemoteAddr());
        var options = passkeys.startAuthentication();
        challenges.put(request.getSession(), "authenticate", options.toJson(), null, null);
        return json.readTree(options.toCredentialsGetJson());
    }

    @PostMapping("/passkeys/authentication/verify")
    Map<String, Object> authenticationVerify(@RequestBody String response, HttpServletRequest request, HttpServletResponse servletResponse) {
        limiter.check(request.getRemoteAddr());
        var challenge = challenges.consume(request.getSession(), "authenticate", null);
        if (response.length() > 65_536) throw PasskeyService.invalid();
        User user = passkeys.finishAuthentication(challenge.optionsJson(), response);
        authentication.signedIn(request, servletResponse, user);
        return Map.of("authenticated", true, "mfaRequired", false, "email", user.getEmail(), "role", AuthController.role(user));
    }

    @GetMapping("/passkeys")
    java.util.List<PasskeyService.View> list(Authentication auth) {
        return passkeys.list(current(auth).id);
    }

    @PatchMapping("/passkeys/{id}")
    PasskeyService.View rename(@PathVariable Long id, @RequestBody Rename body, Authentication auth) {
        return passkeys.rename(current(auth).id, id, body.name());
    }

    @DeleteMapping("/passkeys/{id}")
    ResponseEntity<Void> delete(@PathVariable Long id, @RequestBody Reauthentication body, HttpServletRequest request, Authentication auth) {
        limiter.check(request.getRemoteAddr());
        passkeys.delete(current(auth).id, id, body.currentPassword(), body.code());
        return ResponseEntity.noContent().build();
    }
}
