package au.edu.oshc.smartguide;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Uses sessions only for CSRF and short-lived MFA/WebAuthn challenges. */
@Component
class AuthenticationFlow {
    private final TokenService tokens;
    private final AuthCookies cookies;
    AuthenticationFlow(TokenService tokens, AuthCookies cookies) {
        this.tokens = tokens;
        this.cookies = cookies;
    }
    static HttpSession fresh(HttpServletRequest request) {
        HttpSession previous = request.getSession(false);
        if (previous != null) previous.invalidate();
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(true);
        session.setMaxInactiveInterval(60 * 60);
        return session;
    }
    private void clearPrevious(HttpServletRequest request, HttpServletResponse response) {
        if (request.getCookies() != null) {
            for (var cookie : request.getCookies()) {
                if ("SG_ACCESS".equals(cookie.getName())) tokens.revoke(cookie.getValue(), TokenService.ACCESS);
                if ("SG_REFRESH".equals(cookie.getName())) tokens.revoke(cookie.getValue(), TokenService.REFRESH);
            }
        }
        cookies.clearAll(response);
    }
    void pending(HttpServletRequest request, HttpServletResponse response, String kind, String email, int seconds) {
        clearPrevious(request, response);
        HttpSession session = fresh(request);
        session.setAttribute(kind, email);
        session.setMaxInactiveInterval(seconds);
    }
    void signedIn(HttpServletRequest request, HttpServletResponse response, User user) {
        clearPrevious(request, response);
        fresh(request);
        cookies.issue(response, tokens.createAccessToken(user), tokens.createRefreshToken(user));
    }
}
