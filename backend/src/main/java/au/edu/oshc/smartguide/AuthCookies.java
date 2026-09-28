package au.edu.oshc.smartguide;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class AuthCookies {
    private final boolean secure;
    private final String sameSite;
    private final int accessSeconds;
    private final int refreshSeconds;
    AuthCookies(@Value("${app.cookie-secure:false}") boolean secure,
                @Value("${app.cookie-same-site:Lax}") String sameSite,
                @Value("${app.access-token-minutes:15}") long accessMinutes,
                @Value("${app.refresh-token-days:14}") long refreshDays) {
        this.secure=secure; this.sameSite=sameSite;
        this.accessSeconds=(int)Math.min(Integer.MAX_VALUE, accessMinutes*60);
        this.refreshSeconds=(int)Math.min(Integer.MAX_VALUE, refreshDays*86400);
    }

    void add(HttpServletResponse response, String name, String value, int maxAge) {
        StringBuilder c=new StringBuilder(name).append('=').append(value)
                .append("; Path=/; Max-Age=").append(maxAge)
                .append("; HttpOnly; SameSite=").append(sameSite);
        if(secure)c.append("; Secure");
        response.addHeader("Set-Cookie", c.toString());
    }
    void clear(HttpServletResponse response,String name){add(response,name,"",0);}
    void issue(HttpServletResponse response,String access,String refresh){
        add(response,"SG_ACCESS",access,accessSeconds);
        add(response,"SG_REFRESH",refresh,refreshSeconds);
    }
    void clearAll(HttpServletResponse response){clear(response,"SG_ACCESS");clear(response,"SG_REFRESH");}
}
