package au.edu.oshc.smartguide;

import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.code.CodeVerifier;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.DefaultCodeVerifier;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import dev.samstevens.totp.time.TimeProvider;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Service
class MfaService {
    private final SecretGenerator secretGenerator;
    private final CodeVerifier codeVerifier;
    MfaService(){secretGenerator=new DefaultSecretGenerator();TimeProvider tp=new SystemTimeProvider();CodeGenerator cg=new DefaultCodeGenerator();codeVerifier=new DefaultCodeVerifier(cg,tp);}
    String secret(){return secretGenerator.generate();}
    boolean valid(String secret,String code){return secret!=null&&!secret.isBlank()&&code!=null&&code.matches("\\d{6}")&&codeVerifier.isValidCode(secret,code);}
    String uri(String email,String secret){String issuer=URLEncoder.encode("OSHC SmartGuide",StandardCharsets.UTF_8);String e=URLEncoder.encode(email,StandardCharsets.UTF_8);return "otpauth://totp/"+issuer+":"+e+"?secret="+secret+"&issuer="+issuer+"&algorithm=SHA1&digits=6&period=30";}
}
