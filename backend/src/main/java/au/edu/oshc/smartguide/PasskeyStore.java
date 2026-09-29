package au.edu.oshc.smartguide;

import com.yubico.webauthn.CredentialRepository;
import com.yubico.webauthn.RegisteredCredential;
import com.yubico.webauthn.data.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.stream.Collectors;

@Component
@Transactional(readOnly = true)
class PasskeyStore implements CredentialRepository {
    private final UserRepository users;
    private final PasskeyCredentialRepository credentials;
    PasskeyStore(UserRepository users, PasskeyCredentialRepository credentials) {
        this.users = users;
        this.credentials = credentials;
    }
    static ByteArray bytes(String value) {
        try { return ByteArray.fromBase64Url(value); }
        catch (Exception e) { throw new IllegalArgumentException("Invalid credential encoding."); }
    }
    @Override public Set<PublicKeyCredentialDescriptor> getCredentialIdsForUsername(String username) {
        return users.findByEmail(username).map(u -> credentials.findByUserIdOrderByCreatedAtAsc(u.id).stream()
                .map(c -> PublicKeyCredentialDescriptor.builder().id(bytes(c.credentialId)).build())
                .collect(Collectors.toSet())).orElseGet(Set::of);
    }
    @Override public Optional<ByteArray> getUserHandleForUsername(String username) {
        return users.findByEmail(username).map(u -> u.passkeyUserHandle).map(PasskeyStore::bytes);
    }
    @Override public Optional<String> getUsernameForUserHandle(ByteArray userHandle) {
        return users.findByPasskeyUserHandle(userHandle.getBase64Url()).map(User::getEmail);
    }
    @Override public Optional<RegisteredCredential> lookup(ByteArray credentialId, ByteArray userHandle) {
        return credentials.findByCredentialId(credentialId.getBase64Url())
                .filter(c -> userHandle.getBase64Url().equals(c.user.passkeyUserHandle)).map(this::registered);
    }
    @Override public Set<RegisteredCredential> lookupAll(ByteArray credentialId) {
        return credentials.findByCredentialId(credentialId.getBase64Url()).map(this::registered).map(Set::of).orElseGet(Set::of);
    }
    private RegisteredCredential registered(PasskeyCredential c) {
        return RegisteredCredential.builder().credentialId(bytes(c.credentialId))
                .userHandle(bytes(c.user.passkeyUserHandle)).publicKeyCose(bytes(c.publicKeyCose))
                .signatureCount(c.signatureCount).backupEligible(c.backupEligible).backupState(c.backedUp).build();
    }
}
