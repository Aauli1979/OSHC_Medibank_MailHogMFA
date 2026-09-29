package au.edu.oshc.smartguide;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded per-process limiter. Production clusters should also rate-limit at their trusted proxy. */
@Component
class PasskeyRateLimiter {
    private final Map<String, Window> windows = new LinkedHashMap<>();
    private record Window(long until, int count) {}
    synchronized void check(String remoteAddress) {
        long now = System.currentTimeMillis();
        windows.entrySet().removeIf(e -> e.getValue().until() <= now);
        Window window = windows.getOrDefault(remoteAddress, new Window(now + 60_000, 0));
        if (window.count() >= 90 || (!windows.containsKey(remoteAddress) && windows.size() >= 10_000)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many passkey requests. Please wait one minute.");
        }
        windows.put(remoteAddress, new Window(window.until(), window.count() + 1));
    }
}
