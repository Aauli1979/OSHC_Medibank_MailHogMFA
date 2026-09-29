package au.edu.oshc.smartguide;

import com.yubico.webauthn.*;
import com.yubico.webauthn.data.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
class PasskeyService {
    private final UserRepository users;
    private final PasskeyCredentialRepository credentials;
    private final RelyingParty relyingParty;
    private final BCryptPasswordEncoder passwords;
    private final MfaService mfa;
    private final SecureRandom random = new SecureRandom();

    PasskeyService(UserRepository users, PasskeyCredentialRepository credentials, RelyingParty relyingParty,
                   BCryptPasswordEncoder passwords, MfaService mfa) {
        this.users = users; this.credentials = credentials; this.relyingParty = relyingParty;
        this.passwords = passwords; this.mfa = mfa;
    }

    static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Passkey verification failed. Please start again.");
    }

    record View(Long id, String name, Instant createdAt, Instant lastUsedAt, boolean backupEligible, boolean backedUp) {}
    private View view(PasskeyCredential c) {
        return new View(c.id, c.name, c.createdAt, c.lastUsedAt, c.backupEligible, c.backedUp);
    }

    private String validName(String name) {
        if (name == null || name.isBlank() || name.trim().length() > 80
                || name.chars().anyMatch(Character::isISOControl)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a passkey name between 1 and 80 characters.");
        }
        return name.trim();
    }

    private void reauthenticate(User user, String password, String code) {
        boolean correct;
        try { correct = password != null && passwords.matches(password, user.getPasswordHash()); }
        catch (IllegalArgumentException e) { correct = false; }
        if (!correct || (user.isMfaEnabled()
                && (code == null || !code.matches("[0-9]{6}") || !mfa.valid(
                user.getMfaSecret(), code)))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Check your current password and authenticator code.");
        }
    }

    @Transactional
    PublicKeyCredentialCreationOptions startRegistration(Long userId, String name, String password, String code) {
        validName(name);
        User user = users.lockById(userId).orElseThrow(PasskeyService::invalid);
        if (user.isRegistrationMfaPending()) throw invalid();
        reauthenticate(user, password, code);
        checkCapacity(user.id);
        if (user.passkeyUserHandle == null) {
            byte[] handle = new byte[32];
            random.nextBytes(handle);
            user.passkeyUserHandle = new ByteArray(handle).getBase64Url();
            users.saveAndFlush(user);
        }
        return relyingParty.startRegistration(StartRegistrationOptions.builder()
                .user(UserIdentity.builder().name(user.getEmail()).displayName(user.getEmail())
                        .id(PasskeyStore.bytes(user.passkeyUserHandle)).build())
                .authenticatorSelection(AuthenticatorSelectionCriteria.builder()
                        .residentKey(ResidentKeyRequirement.REQUIRED)
                        .userVerification(UserVerificationRequirement.REQUIRED).build())
                .timeout(300_000).build());
    }

    private void checkCapacity(Long userId) {
        if (credentials.countByUserId(userId) >= 10) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You can save up to 10 passkeys. Remove one before adding another.");
        }
    }

    @Transactional
    View finishRegistration(Long userId, String name, String optionsJson, String responseJson) {
        User user = users.lockById(userId).orElseThrow(PasskeyService::invalid);
        if (user.isRegistrationMfaPending()) throw invalid();
        checkCapacity(user.id);
        RegistrationResult result;
        try {
            PublicKeyCredentialCreationOptions options = PublicKeyCredentialCreationOptions.fromJson(optionsJson);
            if (!options.getUser().getId().getBase64Url().equals(user.passkeyUserHandle)) throw invalid();
            result = relyingParty.finishRegistration(FinishRegistrationOptions.builder().request(options)
                    .response(PublicKeyCredential.parseRegistrationResponseJson(responseJson)).build());
            if (!result.isUserVerified()) throw invalid();
        } catch (Exception e) { throw invalid(); }
        String credentialId = result.getKeyId().getId().getBase64Url();
        if (credentials.findByCredentialId(credentialId).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This passkey is already registered.");
        }
        PasskeyCredential c = new PasskeyCredential();
        c.user = user; c.credentialId = credentialId;
        c.publicKeyCose = result.getPublicKeyCose().getBase64Url();
        c.signatureCount = result.getSignatureCount();
        c.name = validName(name); c.createdAt = Instant.now();
        c.backupEligible = result.isBackupEligible(); c.backedUp = result.isBackedUp();
        c.transports = result.getKeyId().getTransports().map(ts -> ts.stream()
                .map(AuthenticatorTransport::getId).collect(Collectors.joining(","))).orElse("");
        return view(credentials.saveAndFlush(c));
    }

    AssertionRequest startAuthentication() {
        // No account identifier is accepted: discoverable credentials identify the account.
        return relyingParty.startAssertion(StartAssertionOptions.builder()
                .userVerification(UserVerificationRequirement.REQUIRED).timeout(300_000).build());
    }

    @Transactional
    User finishAuthentication(String optionsJson, String responseJson) {
        try {
            var response = PublicKeyCredential.parseAssertionResponseJson(responseJson);
            // Lock before verification so two concurrent assertions cannot reuse a stale counter.
            PasskeyCredential c = credentials.lockByCredentialId(response.getId().getBase64Url())
                    .orElseThrow(PasskeyService::invalid);
            AssertionResult result = relyingParty.finishAssertion(FinishAssertionOptions.builder()
                    .request(AssertionRequest.fromJson(optionsJson)).response(response).build());
            if (c.user.isRegistrationMfaPending() || !result.isSuccess() || !result.isUserVerified()
                    || !Objects.equals(c.user.passkeyUserHandle, result.getUserHandle().getBase64Url())) throw invalid();
            c.signatureCount = result.getSignatureCount();
            c.backedUp = result.isBackedUp();
            c.lastUsedAt = Instant.now();
            credentials.saveAndFlush(c);
            return c.user;
        } catch (Exception e) { throw invalid(); }
    }

    @Transactional(readOnly = true)
    List<View> list(Long userId) {
        return credentials.findByUserIdOrderByCreatedAtAsc(userId).stream().map(this::view).toList();
    }

    @Transactional
    View rename(Long userId, Long credentialId, String name) {
        PasskeyCredential c = credentials.findByIdAndUserId(credentialId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Passkey not found."));
        c.name = validName(name);
        return view(credentials.save(c));
    }

    @Transactional
    void delete(Long userId, Long credentialId, String password, String code) {
        User user = users.lockById(userId).orElseThrow(PasskeyService::invalid);
        if (user.isRegistrationMfaPending()) throw invalid();
        reauthenticate(user, password, code);
        PasskeyCredential c = credentials.findByIdAndUserId(credentialId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Passkey not found."));
        credentials.delete(c);
    }
}
