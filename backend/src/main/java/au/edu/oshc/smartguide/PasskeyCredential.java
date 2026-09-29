package au.edu.oshc.smartguide;

import jakarta.persistence.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Entity
@Table(name = "passkey_credentials", indexes = @Index(name = "idx_passkey_user", columnList = "user_id"))
class PasskeyCredential {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
    @ManyToOne(optional = false) @JoinColumn(name = "user_id", nullable = false) User user;
    @Column(name = "credential_id", nullable = false, unique = true, length = 1400) String credentialId;
    @Column(nullable = false, length = 4096) String publicKeyCose;
    @Column(nullable = false) long signatureCount;
    @Column(nullable = false, length = 80) String name;
    @Column(length = 512) String transports;
    @Column(nullable = false) boolean backupEligible;
    @Column(nullable = false) boolean backedUp;
    @Column(nullable = false) Instant createdAt;
    Instant lastUsedAt;
    @Version long version;
}
interface PasskeyCredentialRepository extends JpaRepository<PasskeyCredential, Long> {
    List<PasskeyCredential> findByUserIdOrderByCreatedAtAsc(Long userId);
    Optional<PasskeyCredential> findByCredentialId(String credentialId);
    Optional<PasskeyCredential> findByIdAndUserId(Long id, Long userId);
    long countByUserId(Long userId);
    void deleteByUserId(Long userId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from PasskeyCredential c where c.credentialId = :credentialId")
    Optional<PasskeyCredential> lockByCredentialId(@Param("credentialId") String credentialId);
}
