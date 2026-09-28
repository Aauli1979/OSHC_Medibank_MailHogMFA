package au.edu.oshc.smartguide;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
class JwtCookieFilter extends OncePerRequestFilter {
    private final TokenService tokenService;
    JwtCookieFilter(TokenService tokenService){this.tokenService=tokenService;}

    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        String raw=getCookie(request,"SG_ACCESS");
        if(raw!=null && SecurityContextHolder.getContext().getAuthentication()==null){
            User u=tokenService.userFromAccessToken(raw);
            if(u!=null){
                String role=u.getRole()==null||u.getRole().isBlank()?"STUDENT":u.getRole();
                var auth=new UsernamePasswordAuthenticationToken(
                        String.valueOf(u.getId()), null,
                        List.of(new SimpleGrantedAuthority("ROLE_"+role)));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }
        chain.doFilter(request,response);
    }

    private String getCookie(HttpServletRequest r,String name){
        if(r.getCookies()==null)return null;
        for(Cookie c:r.getCookies())if(name.equals(c.getName()))return c.getValue();
        return null;
    }
}
