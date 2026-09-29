package au.edu.oshc.smartguide;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.util.Map;
import java.util.IdentityHashMap;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:passkey-tests;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.webauthn.rp-id=localhost", "app.webauthn.origins=http://localhost:5173"
})
@AutoConfigureMockMvc
class PasskeyIntegrationTest {
    private static final String BASE = "/api/auth/passkeys";
    private static final String PASSWORD = "A-strong!Password123";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired PasskeyCredentialRepository keys;
    @Autowired BCryptPasswordEncoder encoder;
    @Autowired MfaService mfa;
    @Autowired TokenService tokenService;
    final Map<MockHttpSession, Cookie> accessCookies = new IdentityHashMap<>();
    User user;
    MockHttpSession session;
    String remote;

    @BeforeEach void setUp() {
        user = new User(); user.setEmail(UUID.randomUUID() + "@example.test");
        user.setPasswordHash(encoder.encode(PASSWORD)); user.setFullName("Test student");
        user.setMfaSecret(mfa.secret()); users.saveAndFlush(user);
        session = new MockHttpSession(); authorize(session, user);
        remote = UUID.randomUUID().toString(); // Isolated limiter bucket per test.
    }

    private ResultActions request(MockHttpServletRequestBuilder request, MockHttpSession session) throws Exception {
        Cookie access = accessCookies.get(session);
        if (access != null) request.cookie(access);
        return mvc.perform(request.session(session).with(csrf()).with(r -> { r.setRemoteAddr(remote); return r; }))
                .andDo(result -> {
                    MockHttpSession target = (MockHttpSession) result.getRequest().getSession(false);
                    for (String header : result.getResponse().getHeaders("Set-Cookie")) {
                        var cookie = java.net.HttpCookie.parse(header).getFirst();
                        if (cookie.getName().equals("SG_ACCESS")) {
                            if (cookie.getValue().isEmpty()) accessCookies.remove(target);
                            else accessCookies.put(target, new Cookie("SG_ACCESS", cookie.getValue()));
                        }
                    }
                });
    }
    private void authorize(MockHttpSession target, User account) {
        accessCookies.put(target, new Cookie("SG_ACCESS", tokenService.createAccessToken(account)));
    }
    private String body(Object value) throws Exception { return json.writeValueAsString(value); }
    private MockHttpServletRequestBuilder postJson(String path, String value) {
        return post(path).contentType("application/json").content(value);
    }
    private JsonNode options() throws Exception {
        return options(session);
    }
    private JsonNode options(MockHttpSession target) throws Exception {
        return json.readTree(request(post(BASE + "/authentication/options"), target).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }
    private JsonNode registrationOptions() throws Exception {
        return json.readTree(request(postJson(BASE + "/registration/options",
                body(Map.of("name", "My laptop", "currentPassword", PASSWORD))), session)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private long register(VirtualAuthenticator authenticator) throws Exception {
        String response = authenticator.register(registrationOptions());
        return json.readTree(request(postJson(BASE + "/registration/verify", response), session)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asLong();
    }

    @Test void registrationAndUsernamelessLoginUseRealSignaturesAndRotateSession() throws Exception {
        VirtualAuthenticator authenticator = new VirtualAuthenticator();
        long id = register(authenticator);
        MockHttpSession anonymous = new MockHttpSession();
        anonymous.setAttribute("PENDING", "old-account@example.test");
        anonymous.setAttribute("MFA_ENABLE_SECRET", "old-secret");
        MvcResult result = request(postJson(BASE + "/authentication/verify",
                authenticator.authenticate(options(anonymous), 1)), anonymous)
                .andExpect(status().isOk()).andExpect(jsonPath("$.authenticated").value(true)).andReturn();
        MockHttpSession loggedIn = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(anonymous.isInvalid()).isTrue();
        assertThat(loggedIn.getAttribute("AUTH")).isNull();
        assertThat(tokenService.userFromAccessToken(accessCookies.get(loggedIn).getValue()).id).isEqualTo(user.id);
        assertThat(result.getResponse().getHeaders("Set-Cookie")).anyMatch(h -> h.startsWith("SG_ACCESS=") && h.contains("HttpOnly"));
        assertThat(loggedIn.getAttribute("PENDING")).isNull();
        assertThat(loggedIn.getAttribute("MFA_ENABLE_SECRET")).isNull();
        assertThat(loggedIn.getAttribute(PasskeyChallenges.KEY)).isNull();
        PasskeyCredential stored = keys.findById(id).orElseThrow();
        assertThat(stored.signatureCount).isEqualTo(1);
        assertThat(stored.lastUsedAt).isNotNull();
        request(get("/api/profile"), loggedIn).andExpect(status().isOk()).andExpect(jsonPath("$.email").value(user.getEmail()));
    }

    @Test void csrfIsRequiredAndRealCsrfEndpointWorks() throws Exception {
        mvc.perform(post(BASE + "/authentication/options")).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/login").contentType("application/json").content("{}")).andExpect(status().isForbidden());
        MvcResult response = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        JsonNode token = json.readTree(response.getResponse().getContentAsString());
        mvc.perform(post(BASE + "/authentication/options")
                .session((MockHttpSession) response.getRequest().getSession(false))
                .header(token.get("headerName").asText(), token.get("token").asText()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.publicKey.userVerification").value("required"));
    }

    @Test void pendingMfaCannotRegisterOrManageCredentials() throws Exception {
        accessCookies.remove(session); session.setAttribute("PENDING", user.getEmail());
        session.setAttribute("AUTH", user.getEmail()); // A legacy AUTH attribute must not grant access.
        request(postJson(BASE + "/registration/options", body(Map.of("name","Key","currentPassword",PASSWORD))), session)
                .andExpect(status().isUnauthorized());
        request(get(BASE), session).andExpect(status().isUnauthorized());
    }

    @Test void enrollmentRequiresPasswordAndTotpWhenEnabled() throws Exception {
        user.setMfaEnabled(true); users.saveAndFlush(user);
        request(postJson(BASE + "/registration/options", body(Map.of("name","Key","currentPassword",PASSWORD))), session)
                .andExpect(status().isUnauthorized());
        String code = new DefaultCodeGenerator().generate(user.getMfaSecret(), System.currentTimeMillis() / 30_000);
        request(postJson(BASE + "/registration/options", body(Map.of("name","Key","currentPassword","wrong","code",code))), session)
                .andExpect(status().isUnauthorized());
        request(postJson(BASE + "/registration/options", body(Map.of("name","Key","currentPassword",PASSWORD,"code",code))), session)
                .andExpect(status().isOk()).andExpect(jsonPath("$.publicKey.authenticatorSelection.residentKey").value("required"))
                .andExpect(jsonPath("$.publicKey.authenticatorSelection.userVerification").value("required"));
    }

    @ParameterizedTest
    @ValueSource(strings={"origin","rpId","uv","presence","challenge","signature","handle","crossOrigin"})
    void invalidAssertionsAreRejectedAndConsumed(String fault) throws Exception {
        VirtualAuthenticator authenticator = new VirtualAuthenticator(); register(authenticator);
        MockHttpSession anonymous = new MockHttpSession();
        JsonNode options = options(anonymous);
        String valid = authenticator.authenticate(options, 1);
        String response = authenticator.authenticate(options, 1,
                fault.equals("origin") ? "https://attacker.example" : "http://localhost:5173",
                fault.equals("rpId") ? "attacker.example" : "localhost",
                fault.equals("uv") ? 1 : fault.equals("presence") ? 4 : 5,
                fault.equals("handle") ? VirtualAuthenticator.b64(new byte[32]) : authenticator.handle,
                fault.equals("signature"));
        if (fault.equals("challenge") || fault.equals("crossOrigin")) {
            var tree = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(response);
            var client = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(java.util.Base64.getUrlDecoder()
                    .decode(tree.at("/response/clientDataJSON").asText()));
            if (fault.equals("challenge")) client.put("challenge", "wrong-challenge");
            else client.put("crossOrigin", true);
            ((com.fasterxml.jackson.databind.node.ObjectNode)tree.get("response"))
                    .put("clientDataJSON", VirtualAuthenticator.b64(json.writeValueAsBytes(client)));
            response = body(tree);
        }
        request(postJson(BASE + "/authentication/verify", response), anonymous).andExpect(status().isBadRequest());
        assertThat(anonymous.getAttribute("AUTH")).isNull();
        request(postJson(BASE + "/authentication/verify", valid), anonymous).andExpect(status().isBadRequest());
    }

    @ParameterizedTest @ValueSource(strings={"origin","rpId","uv","presence"})
    void invalidRegistrationsAreRejected(String fault) throws Exception {
        VirtualAuthenticator authenticator = new VirtualAuthenticator();
        String response = authenticator.register(registrationOptions(),
                fault.equals("origin") ? "https://attacker.example" : "http://localhost:5173",
                fault.equals("rpId") ? "attacker.example" : "localhost",
                fault.equals("uv") ? 0x41 : fault.equals("presence") ? 0x44 : 0x45);
        request(postJson(BASE + "/registration/verify", response), session).andExpect(status().isBadRequest());
        assertThat(keys.countByUserId(user.id)).isZero();
    }

    @Test void expiredWrongPurposeAndReplayedChallengesFail() throws Exception {
        VirtualAuthenticator authenticator = new VirtualAuthenticator();
        String response = authenticator.register(registrationOptions());
        PasskeyChallenges.Challenge challenge = (PasskeyChallenges.Challenge)session.getAttribute(PasskeyChallenges.KEY);
        session.setAttribute(PasskeyChallenges.KEY, new PasskeyChallenges.Challenge("register",
                challenge.optionsJson(), user.id, challenge.name(), 0));
        request(postJson(BASE + "/registration/verify", response), session).andExpect(status().isBadRequest());
        response = authenticator.register(registrationOptions());
        request(postJson(BASE + "/authentication/verify", response), session).andExpect(status().isBadRequest());
        response = authenticator.register(registrationOptions());
        request(postJson(BASE + "/registration/verify", response), session).andExpect(status().isOk());
        request(postJson(BASE + "/registration/verify", response), session).andExpect(status().isBadRequest());
        assertThat(keys.countByUserId(user.id)).isEqualTo(1);
    }

    @Test void challengesAreBoundToTheirSessionAndAccount() throws Exception {
        VirtualAuthenticator authenticator = new VirtualAuthenticator();
        String response = authenticator.register(registrationOptions());
        MockHttpSession other = new MockHttpSession(); authorize(other, user);
        request(postJson(BASE + "/registration/verify", response), other).andExpect(status().isBadRequest());
        User second = anotherUser();
        authorize(session, second);
        request(postJson(BASE + "/registration/verify", response), session).andExpect(status().isBadRequest());
    }

    @Test void credentialCounterMustAdvanceButZeroCountersAreSupported() throws Exception {
        VirtualAuthenticator authenticator = new VirtualAuthenticator(); register(authenticator);
        for (int counter : new int[]{0, 0, 2}) {
            MockHttpSession anonymous = new MockHttpSession();
            request(postJson(BASE + "/authentication/verify", authenticator.authenticate(options(anonymous), counter)), anonymous)
                    .andExpect(status().isOk());
        }
        MockHttpSession anonymous = new MockHttpSession();
        request(postJson(BASE + "/authentication/verify", authenticator.authenticate(options(anonymous), 1)), anonymous)
                .andExpect(status().isBadRequest());
    }

    @Test void ownerCanRenameAndDeleteButAnotherAccountCannot() throws Exception {
        VirtualAuthenticator authenticator = new VirtualAuthenticator(); long id = register(authenticator);
        request(patch(BASE + "/" + id).contentType("application/json").content(body(Map.of("name","Renamed"))), session)
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Renamed"))
                .andExpect(jsonPath("$.publicKeyCose").doesNotExist());
        MockHttpSession other = new MockHttpSession(); authorize(other, anotherUser());
        request(patch(BASE + "/" + id).contentType("application/json").content(body(Map.of("name","Hijacked"))), other)
                .andExpect(status().isNotFound());
        request(delete(BASE + "/" + id).contentType("application/json").content(body(Map.of("currentPassword",PASSWORD))), other)
                .andExpect(status().isNotFound());
        request(delete(BASE + "/" + id).contentType("application/json").content(body(Map.of("currentPassword","wrong"))), session)
                .andExpect(status().isUnauthorized());
        request(delete(BASE + "/" + id).contentType("application/json").content(body(Map.of("currentPassword",PASSWORD))), session)
                .andExpect(status().isNoContent());
        MockHttpSession anonymous = new MockHttpSession();
        request(postJson(BASE + "/authentication/verify", authenticator.authenticate(options(anonymous), 1)), anonymous)
                .andExpect(status().isBadRequest());
    }

    @Test void emailChangesPreserveKeysAndAccountDeletionRemovesThem() throws Exception {
        VirtualAuthenticator authenticator = new VirtualAuthenticator(); register(authenticator);
        String email = UUID.randomUUID() + "@example.test";
        request(postJson("/api/profile/email", body(Map.of("currentPassword",PASSWORD,"newEmail",email))), session)
                .andExpect(status().isOk());
        MockHttpSession anonymous = new MockHttpSession();
        MvcResult result = request(postJson(BASE + "/authentication/verify", authenticator.authenticate(options(anonymous),1)), anonymous)
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value(email)).andReturn();
        MockHttpSession loggedIn = (MockHttpSession)result.getRequest().getSession(false);
        request(delete("/api/profile").contentType("application/json").content(body(Map.of("password",PASSWORD))), loggedIn)
                .andExpect(status().isOk());
        assertThat(keys.countByUserId(user.id)).isZero();
        assertThat(users.findById(user.id)).isEmpty();
    }

    @Test void existingPasswordAndTotpFlowsStillWorkWithFreshSessions() throws Exception {
        MockHttpSession anonymous = new MockHttpSession();
        MvcResult login = request(postJson("/api/auth/login", body(Map.of("email",user.getEmail(),"password",PASSWORD))), anonymous)
                .andExpect(status().isOk()).andExpect(jsonPath("$.authenticated").value(true)).andReturn();
        assertThat(anonymous.isInvalid()).isTrue();
        assertThat(login.getRequest().getSession().getAttribute("AUTH")).isNull();
        assertThat(login.getResponse().getHeaders("Set-Cookie")).anyMatch(h -> h.startsWith("SG_ACCESS=") && !h.startsWith("SG_ACCESS=;"));
        user.setMfaEnabled(true); users.saveAndFlush(user);
        login = request(postJson("/api/auth/login", body(Map.of("email",user.getEmail(),"password",PASSWORD))), new MockHttpSession())
                .andExpect(status().isOk()).andExpect(jsonPath("$.mfaRequired").value(true)).andReturn();
        MockHttpSession pending = (MockHttpSession)login.getRequest().getSession(false);
        assertThat(pending.getAttribute("AUTH")).isNull();
        String code = new DefaultCodeGenerator().generate(user.getMfaSecret(),System.currentTimeMillis()/30_000);
        request(postJson("/api/auth/mfa/verify",body(Map.of("code",code))),pending)
                .andExpect(status().isOk()).andExpect(jsonPath("$.authenticated").value(true));
        assertThat(pending.isInvalid()).isTrue();
    }

    @Test void publicEndpointsAreRateLimited() {
        PasskeyRateLimiter limiter = new PasskeyRateLimiter();
        for (int i=0;i<90;i++) limiter.check("test");
        assertThatThrownBy(() -> limiter.check("test"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("429");
    }

    private User anotherUser() {
        User second = new User(); second.setEmail(UUID.randomUUID()+"@example.test");
        second.setPasswordHash(user.getPasswordHash()); return users.saveAndFlush(second);
    }
}
