package au.edu.oshc.smartguide;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:mailhog-mfa-tests;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "app.webauthn.rp-id=localhost", "app.webauthn.origins=http://localhost:5173",
    "app.frontend-url=http://localhost:5173", "app.mail-from=oshc-smartguide@localhost"
})
@AutoConfigureMockMvc
class MailHogMfaIntegrationTest {
    static final String PASSWORD = "A-strong!Password123";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired AuthTokenRepository tokens;
    @Autowired TokenService tokenService;
    @Autowired PasswordResetTokenRepository resets;
    @Autowired BCryptPasswordEncoder encoder;
    @Autowired MfaService mfa;
    @MockitoBean JavaMailSender mail;

    MockHttpServletRequestBuilder postBody(String path, Object body) throws Exception {
        return post(path).with(csrf()).contentType("application/json").content(json.writeValueAsString(body));
    }
    JsonNode body(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsString()); }
    Cookie cookie(MvcResult result, String name) {
        Cookie found = null;
        for (String header : result.getResponse().getHeaders("Set-Cookie")) {
            var c = java.net.HttpCookie.parse(header).getFirst();
            if (c.getName().equals(name)) found = new Cookie(name, c.getValue());
        }
        assertThat(found).isNotNull();
        return found;
    }
    MockHttpSession session(MvcResult result) { return (MockHttpSession) result.getRequest().getSession(false); }
    User account() {
        User u = new User(); u.setEmail(UUID.randomUUID() + "@example.test");
        u.setPasswordHash(encoder.encode(PASSWORD)); u.setMfaSecret(mfa.secret());
        return users.saveAndFlush(u);
    }
    String code(User u) throws Exception { return new DefaultCodeGenerator().generate(u.getMfaSecret(), System.currentTimeMillis()/30_000); }
    MvcResult login(User u) throws Exception {
        return mvc.perform(postBody("/api/auth/login", Map.of("email", u.getEmail(), "password", PASSWORD)))
                .andExpect(status().isOk()).andReturn();
    }

    @Test void registrationRequiresTotpAndCanResumeFromLogin() throws Exception {
        String email = UUID.randomUUID() + "@example.test";
        MvcResult start = mvc.perform(postBody("/api/auth/register", Map.of("email",email,"password",PASSWORD)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.registrationMfaRequired").value(true)).andReturn();
        assertThat(cookie(start,"SG_ACCESS").getValue()).isEmpty();
        mvc.perform(get("/api/profile").session(session(start))).andExpect(status().isUnauthorized());
        mvc.perform(postBody("/api/auth/passkeys/registration/options",Map.of("name","Key","currentPassword",PASSWORD))
                .session(session(start))).andExpect(status().isUnauthorized());
        mvc.perform(postBody("/api/auth/register/mfa/verify",Map.of("code","invalid"))
                .session(session(start))).andExpect(status().isUnauthorized());
        User u = users.findByEmail(email).orElseThrow();
        MvcResult resumed = login(u);
        assertThat(body(resumed).get("registrationMfaRequired").asBoolean()).isTrue();
        MockHttpSession pendingSession = session(resumed);
        MvcResult verified = mvc.perform(postBody("/api/auth/register/mfa/verify",Map.of("code",code(u)))
                .session(pendingSession)).andExpect(status().isOk()).andReturn();
        assertThat(pendingSession.isInvalid()).isTrue();
        assertThat(session(verified).getAttribute("REGISTER_MFA")).isNull();
        u = users.findById(u.id).orElseThrow();
        assertThat(u.isRegistrationMfaPending()).isFalse();
        assertThat(u.isMfaEnabled()).isTrue();
        mvc.perform(get("/api/profile").cookie(cookie(verified,"SG_ACCESS"))).andExpect(status().isOk());
        assertThat(verified.getResponse().getHeaders("Set-Cookie"))
                .anyMatch(h -> h.startsWith("SG_REFRESH=") && h.contains("HttpOnly") && h.contains("SameSite=Lax"));
    }

    @Test void passkeyTokensRefreshRotateLogoutAndRevokeAcrossSessions() throws Exception {
        User u = account();
        MvcResult initial = login(u);
        var authSession = session(initial);
        Cookie access = cookie(initial,"SG_ACCESS");
        Cookie refresh = cookie(initial,"SG_REFRESH");
        MvcResult options = mvc.perform(postBody("/api/auth/passkeys/registration/options",Map.of("name","Laptop","currentPassword",PASSWORD))
                .session(authSession).cookie(access)).andExpect(status().isOk()).andReturn();
        VirtualAuthenticator authenticator = new VirtualAuthenticator();
        mvc.perform(post("/api/auth/passkeys/registration/verify").with(csrf()).session(authSession).cookie(access)
                .contentType("application/json").content(authenticator.register(body(options)))).andExpect(status().isOk());
        var anonymous = new MockHttpSession();
        options = mvc.perform(post("/api/auth/passkeys/authentication/options").with(csrf()).session(anonymous))
                .andExpect(status().isOk()).andReturn();
        MvcResult signedIn = mvc.perform(post("/api/auth/passkeys/authentication/verify").with(csrf()).session(anonymous)
                .contentType("application/json").content(authenticator.authenticate(body(options),1)))
                .andExpect(status().isOk()).andReturn();
        Cookie passkeyAccess = cookie(signedIn,"SG_ACCESS"), passkeyRefresh = cookie(signedIn,"SG_REFRESH");
        mvc.perform(get("/api/profile").cookie(passkeyAccess)).andExpect(status().isOk());
        assertThat(tokens.findByTokenHashAndType(passkeyAccess.getValue(),TokenService.ACCESS)).isEmpty();
        AuthToken stored = tokens.findByTokenHashAndType(tokenService.hash(passkeyAccess.getValue()),TokenService.ACCESS).orElseThrow();
        stored.setExpiresAt(0); tokens.saveAndFlush(stored);
        mvc.perform(get("/api/profile").session(session(signedIn)).cookie(passkeyAccess)).andExpect(status().isUnauthorized());
        MvcResult renewed = mvc.perform(post("/api/auth/refresh").with(csrf()).session(session(signedIn)).cookie(passkeyRefresh))
                .andExpect(status().isOk()).andReturn();
        mvc.perform(post("/api/auth/refresh").with(csrf()).cookie(passkeyRefresh)).andExpect(status().isUnauthorized());
        Cookie renewedAccess = cookie(renewed,"SG_ACCESS"), renewedRefresh = cookie(renewed,"SG_REFRESH");
        mvc.perform(get("/api/profile").cookie(renewedAccess)).andExpect(status().isOk());
        mvc.perform(post("/api/auth/logout").with(csrf()).cookie(renewedAccess,renewedRefresh)).andExpect(status().isNoContent());
        mvc.perform(post("/api/auth/refresh").with(csrf()).cookie(renewedRefresh)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/profile").cookie(renewedAccess)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/revoke-all").with(csrf()).cookie(access)).andExpect(status().isOk());
        mvc.perform(get("/api/profile").cookie(access)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/refresh").with(csrf()).cookie(refresh)).andExpect(status().isUnauthorized());
    }

    @Test void pendingAccountCannotUseAnExistingPasskeyOrToken() throws Exception {
        User u=account();
        Cookie access=new Cookie("SG_ACCESS",tokenService.createAccessToken(u));
        var s=new MockHttpSession();
        MvcResult options=mvc.perform(postBody("/api/auth/passkeys/registration/options",Map.of("name","Key","currentPassword",PASSWORD))
                .cookie(access).session(s)).andExpect(status().isOk()).andReturn();
        VirtualAuthenticator authenticator=new VirtualAuthenticator();
        mvc.perform(post("/api/auth/passkeys/registration/verify").with(csrf()).cookie(access).session(s)
                .contentType("application/json").content(authenticator.register(body(options)))).andExpect(status().isOk());
        u=users.findById(u.id).orElseThrow();u.setRegistrationMfaPending(true);users.saveAndFlush(u);
        mvc.perform(get("/api/profile").cookie(access)).andExpect(status().isUnauthorized());
        options=mvc.perform(post("/api/auth/passkeys/authentication/options").with(csrf()).session(s)).andReturn();
        mvc.perform(post("/api/auth/passkeys/authentication/verify").with(csrf()).session(s)
                .contentType("application/json").content(authenticator.authenticate(body(options),1))).andExpect(status().isBadRequest());
    }

    @Test void passwordResetMailHasWorkingLinkAndRevokesTokens() throws Exception {
        User u = account(); MvcResult signedIn = login(u);
        mvc.perform(postBody("/api/auth/forgot-password",Map.of("email",u.getEmail()))).andExpect(status().isOk());
        var captured=ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(captured.capture());
        var message=captured.getValue();
        assertThat(message.getTo()).containsExactly(u.getEmail());
        assertThat(message.getFrom()).isEqualTo("oshc-smartguide@localhost");
        assertThat(message.getText()).contains("http://localhost:5173/reset-password?token=");
        String resetToken=message.getText().split("token=",2)[1].split("\\s",2)[0];
        mvc.perform(postBody("/api/auth/reset-password",Map.of("token",resetToken,"newPassword","New!Password12345")))
                .andExpect(status().isOk());
        assertThat(encoder.matches("New!Password12345",users.findById(u.id).orElseThrow().getPasswordHash())).isTrue();
        assertThat(resets.findByToken(resetToken)).isEmpty();
        mvc.perform(get("/api/profile").cookie(cookie(signedIn,"SG_ACCESS"))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/refresh").with(csrf()).cookie(cookie(signedIn,"SG_REFRESH"))).andExpect(status().isUnauthorized());
        mvc.perform(postBody("/api/auth/reset-password",Map.of("token",resetToken,"newPassword","Other!Password12345")))
                .andExpect(status().isBadRequest());
    }

    @Test void smtpFailureRemovesTheResetTokenAndUnknownAccountSendsNothing() throws Exception {
        mvc.perform(postBody("/api/auth/forgot-password",Map.of("email",UUID.randomUUID()+"@example.test")))
                .andExpect(status().isOk());
        verifyNoInteractions(mail);
        User u=account();
        doThrow(new MailSendException("Local SMTP is unavailable")).when(mail).send(any(SimpleMailMessage.class));
        mvc.perform(postBody("/api/auth/forgot-password",Map.of("email",u.getEmail()))).andExpect(status().isServiceUnavailable());
        assertThat(resets.findAll()).noneMatch(t -> u.getEmail().equals(t.getEmail()));
    }

    @Test void changingEmailKeepsLearningProgressAndPasskeyOriginIsRestricted() throws Exception {
        User u=account();MvcResult signedIn=login(u);Cookie access=cookie(signedIn,"SG_ACCESS");
        mvc.perform(put("/api/progress").with(csrf()).cookie(access).contentType("application/json")
                .content("{\"completedScenarioIds\":[\"gp\"],\"quizBestScore\":80,\"quizAttempts\":1}"))
                .andExpect(status().isNoContent());
        mvc.perform(postBody("/api/profile/email",Map.of("currentPassword",PASSWORD,"newEmail",UUID.randomUUID()+"@example.test"))
                .cookie(access)).andExpect(status().isOk());
        mvc.perform(get("/api/progress").cookie(access)).andExpect(status().isOk()).andExpect(jsonPath("$.quizBestScore").value(80));
        mvc.perform(options("/api/auth/passkeys/1").header("Origin","http://localhost:5173")
                .header("Access-Control-Request-Method","PATCH").header("Access-Control-Request-Headers","X-CSRF-TOKEN"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin","http://localhost:5173"));
        mvc.perform(options("/api/auth/passkeys/1").header("Origin","https://attacker.example")
                .header("Access-Control-Request-Method","PATCH")).andExpect(status().isForbidden());
    }
}
