package au.edu.oshc.smartguide;

import jakarta.persistence.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

@Entity
@Table(name="auth_tokens", indexes={
        @Index(name="idx_auth_token_hash_type", columnList="tokenHash,type", unique=true),
        @Index(name="idx_auth_token_user", columnList="userId")
})
class AuthToken {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY)
    Long id;
    @Column(nullable=false, length=128)
    String tokenHash;
    @Column(nullable=false)
    Long userId;
    @Column(nullable=false, length=16)
    String type;
    @Column(nullable=false)
    long expiresAt;
    @Column(nullable=false)
    boolean revoked;

    public Long getId(){return id;}
    public String getTokenHash(){return tokenHash;}
    public void setTokenHash(String x){tokenHash=x;}
    public Long getUserId(){return userId;}
    public void setUserId(Long x){userId=x;}
    public String getType(){return type;}
    public void setType(String x){type=x;}
    public long getExpiresAt(){return expiresAt;}
    public void setExpiresAt(long x){expiresAt=x;}
    public boolean isRevoked(){return revoked;}
    public void setRevoked(boolean x){revoked=x;}
}

interface AuthTokenRepository extends JpaRepository<AuthToken,Long> {
    Optional<AuthToken> findByTokenHashAndType(String tokenHash, String type);
    List<AuthToken> findAllByUserIdAndRevokedFalse(Long userId);
}
