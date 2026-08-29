package net.xiidea.enginx.infrastructure.persistence.repository;

import net.xiidea.enginx.infrastructure.persistence.entity.CertificateEntity;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CertificateJpaRepository extends JpaRepository<CertificateEntity, UUID> {

    // Domains are fetched with the certificate: a repository returns a complete aggregate rather
    // than one whose contents depend on whether the caller holds a session.
    @Query("select c from CertificateEntity c left join fetch c.domains where c.id = :id")
    Optional<CertificateEntity> findByIdWithDomains(@Param("id") UUID id);

    @Query("select distinct c from CertificateEntity c left join fetch c.domains order by c.name")
    List<CertificateEntity> findAllWithDomains();

    /**
     * Certificates an ACME provider should renew: issued at least once, and now inside the
     * renewal window.
     *
     * <p>Revoked ones are excluded — renewing one would resurrect something an operator withdrew.
     * So are certificates that were never issued: those are either mid-issuance, where the sweep
     * would race the request that created them, or failed, where retrying on a timer spends the
     * authority's rate limit on something that cannot succeed until a human intervenes.
     */
    @Query("select c from CertificateEntity c "
            + "where c.autoRenew = true "
            + "  and c.provider = net.xiidea.enginx.domain.certificate.CertificateProviderKind.ACME "
            + "  and c.revokedAt is null "
            + "  and c.expiresAt is not null "
            + "  and c.expiresAt <= :threshold "
            + "order by c.expiresAt")
    List<CertificateEntity> findDueForRenewal(@Param("threshold") Instant threshold, Limit limit);

    /**
     * Certificates whose stored status no longer matches their dates. Catches the transition into
     * EXPIRING_SOON and EXPIRED without needing a timer per certificate.
     */
    @Query("select c from CertificateEntity c "
            + "where c.revokedAt is null and c.expiresAt is not null "
            + "  and (   (c.expiresAt <= :now and c.status <> net.xiidea.enginx.domain.certificate.CertificateStatus.EXPIRED) "
            + "       or (c.expiresAt >  :now and c.status =  net.xiidea.enginx.domain.certificate.CertificateStatus.EXPIRED)) "
            + "order by c.expiresAt")
    List<CertificateEntity> findWithStaleExpiry(@Param("now") Instant now, Limit limit);

    @Query("select count(s) > 0 from ProxySiteEntity s where s.sslCertificateId = :certificateId")
    boolean isReferencedByAnySite(@Param("certificateId") UUID certificateId);
}
