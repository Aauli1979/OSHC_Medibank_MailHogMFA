package au.edu.oshc.smartguide;

import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.data.RelyingPartyIdentity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.net.URI;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Configuration
class PasskeyConfiguration {
    @Bean
    Set<String> webauthnOrigins(@Value("${app.webauthn.origins}") String configured,
                               @Value("${app.webauthn.rp-id}") String rpId) {
        if (rpId.isBlank() || rpId.contains(":") || rpId.contains("/") || rpId.contains("*")) {
            throw new IllegalArgumentException("WEBAUTHN_RP_ID must be a domain name without scheme or port.");
        }
        Set<String> origins = Arrays.stream(configured.split(",")).map(String::trim).collect(Collectors.toSet());
        for (String origin : origins) {
            URI uri = URI.create(origin);
            String host = uri.getHost();
            boolean local = "http".equals(uri.getScheme()) && "localhost".equals(host);
            if (host == null || (!local && !"https".equals(uri.getScheme()))
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !uri.getRawPath().isEmpty() || (!host.equals(rpId) && !host.endsWith("." + rpId))) {
                throw new IllegalArgumentException("WEBAUTHN_ORIGINS must contain exact HTTPS origins under the RP ID (HTTP localhost is allowed).");
            }
        }
        return Set.copyOf(origins);
    }
    @Bean
    RelyingParty relyingParty(PasskeyStore store, Set<String> webauthnOrigins,
                              @Value("${app.webauthn.rp-id}") String rpId,
                              @Value("${app.webauthn.rp-name}") String rpName) {
        return RelyingParty.builder()
                .identity(RelyingPartyIdentity.builder().id(rpId).name(rpName).build())
                .credentialRepository(store).origins(webauthnOrigins)
                .allowOriginPort(false).allowOriginSubdomain(false)
                .validateSignatureCounter(true).build();
    }
}
