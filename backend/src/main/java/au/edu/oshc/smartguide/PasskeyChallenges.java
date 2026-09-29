package au.edu.oshc.smartguide;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.WebUtils;
import java.io.Serializable;
import java.util.Objects;

@Component
class PasskeyChallenges {
    static final String KEY = "PASSKEY_CEREMONY";
    static final long LIFETIME_MS = 300_000;

    record Challenge(String purpose, String optionsJson, Long userId, String name, long expiresAt) implements Serializable {}

    void put(HttpSession session, String purpose, String optionsJson, Long userId, String name) {
        synchronized (WebUtils.getSessionMutex(session)) {
            session.setAttribute(KEY, new Challenge(purpose, optionsJson, userId, name,
                    System.currentTimeMillis() + LIFETIME_MS));
        }
    }

    Challenge consume(HttpSession session, String purpose, Long userId) {
        synchronized (WebUtils.getSessionMutex(session)) {
            Object stored = session.getAttribute(KEY);
            session.removeAttribute(KEY); // Consume even invalid/failed responses; never reusable.
            if (!(stored instanceof Challenge c) || !purpose.equals(c.purpose())
                    || !Objects.equals(userId, c.userId()) || System.currentTimeMillis() >= c.expiresAt()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Passkey request expired or already used. Please start again.");
            }
            return c;
        }
    }
}
