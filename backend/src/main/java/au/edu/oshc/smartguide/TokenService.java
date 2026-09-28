package au.edu.oshc.smartguide;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

@Service
class TokenService {
    static final String ACCESS = "ACCESS";
    static final String REFRESH = "REFRESH";

    private final AuthTokenRepository tokens;
    private final UserRepository users;
    private final SecureRandom random = new SecureRandom();
    private final long accessMinutes;
    private final long refreshDays;

    TokenService(AuthTokenRepository tokens, UserRepository users,
                 @Value("${app.access-token-minutes:15}") long accessMinutes,
                 @Value("${app.refresh-token-days:14}") long refreshDays) {
        this.tokens = tokens;
        this.users = users;
        this.accessMinutes = accessMinutes;
        this.refreshDays = refreshDays;
    }

    String createAccessToken(User user) {
        return create(user, ACCESS, Instant.now().plusSeconds(accessMinutes * 60));
    }

    String createRefreshToken(User user) {
        return create(user, REFRESH, Instant.now().plusSeconds(refreshDays * 86400));
    }

    private String create(User user, String type, Instant expiresAt) {
        byte[] bytes = new byte[48];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        AuthToken token = new AuthToken();
        token.setTokenHash(hash(raw));
        token.setUserId(user.getId());
        token.setType(type);
        token.setExpiresAt(expiresAt.toEpochMilli());
        token.setRevoked(false);
        tokens.save(token);
        return raw;
    }

    User userFromAccessToken(String raw) {
        return userFromToken(raw, ACCESS);
    }

    User userFromRefreshToken(String raw) {
        return userFromToken(raw, REFRESH);
    }

    private User userFromToken(String raw, String type) {
        if (raw == null || raw.isBlank()) return null;
        AuthToken token = tokens.findByTokenHashAndType(hash(raw), type).orElse(null);
        if (token == null || token.isRevoked() || token.getExpiresAt() <= System.currentTimeMillis()) return null;
        return users.findById(token.getUserId()).orElse(null);
    }

    void revoke(String raw, String type) {
        if (raw == null || raw.isBlank()) return;
        tokens.findByTokenHashAndType(hash(raw), type).ifPresent(t -> {
            t.setRevoked(true);
            tokens.save(t);
        });
    }

    void revokeAllForUser(Long userId) {
        tokens.findAllByUserIdAndRevokedFalse(userId).forEach(t -> {
            t.setRevoked(true);
            tokens.save(t);
        });
    }

    String hash(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return Base64.getEncoder().encodeToString(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
