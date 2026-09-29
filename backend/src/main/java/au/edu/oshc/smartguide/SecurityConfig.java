package au.edu.oshc.smartguide;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.cors.CorsConfiguration;
import java.util.List;
import java.util.Set;

@Configuration
class SecurityConfig {
    @Bean BCryptPasswordEncoder encoder() { return new BCryptPasswordEncoder(12); }
    @Bean FilterRegistrationBean<JwtCookieFilter> cookieFilterRegistration(JwtCookieFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false); // Run once, inside Spring Security's filter chain.
        return registration;
    }
    @Bean SecurityFilterChain security(HttpSecurity http, JwtCookieFilter cookieFilter,
                                       Set<String> webauthnOrigins) throws Exception {
        http.csrf(csrf -> csrf.csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
            .cors(cors -> cors.configurationSource(request -> {
                CorsConfiguration c = new CorsConfiguration();
                c.setAllowedOrigins(List.copyOf(webauthnOrigins));
                c.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
                c.setAllowedHeaders(List.of("Content-Type", "X-CSRF-TOKEN", "X-Requested-With"));
                c.setAllowCredentials(true);
                return c;
            }))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
            // A revoked access token must never fall back to a saved session authentication.
            .securityContext(context -> context.securityContextRepository(new NullSecurityContextRepository()))
            .requestCache(cache -> cache.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/**", "/api/scenarios/**").permitAll()
                .requestMatchers("/api/profile/**", "/api/progress/**").authenticated()
                .anyRequest().permitAll())
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, exception) -> {
                    response.setStatus(401);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"message\":\"Authentication required.\"}");
                })
                .accessDeniedHandler((request, response, exception) -> {
                    response.setStatus(403);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"message\":\"Security token expired or missing. Please try again.\"}");
                }))
            .addFilterBefore(cookieFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
