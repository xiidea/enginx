package net.xiidea.enginx.api.certificate;

import net.xiidea.enginx.api.certificate.dto.CertificateDtos;
import net.xiidea.enginx.application.certificate.CertificateCommands;
import net.xiidea.enginx.application.certificate.CertificateService;
import net.xiidea.enginx.application.certificate.SecretRewrapService;
import net.xiidea.enginx.domain.certificate.Certificate;
import net.xiidea.enginx.domain.certificate.CertificateMetadata;
import net.xiidea.enginx.domain.certificate.CertificateRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/certificates")
@Tag(name = "Certificates", description = "TLS certificate lifecycle")
public class CertificateController {

    private final CertificateService certificates;
    private final CertificateRepository repository;
    private final SecretRewrapService rewrap;
    private final Clock clock;

    public CertificateController(CertificateService certificates, CertificateRepository repository,
                                 SecretRewrapService rewrap, Clock clock) {
        this.certificates = certificates;
        this.repository = repository;
        this.rewrap = rewrap;
        this.clock = clock;
    }

    @GetMapping
    @Operation(summary = "List certificates",
            description = "Metadata only. Private key material is never returned by any endpoint.")
    public List<CertificateDtos.Response> list() {
        return certificates.findAll().stream().map(this::toResponse).toList();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch one certificate")
    public CertificateDtos.Response get(@PathVariable UUID id) {
        return toResponse(certificates.get(id));
    }

    @PostMapping
    @Operation(summary = "Request a certificate over ACME",
            description = "Validates with HTTP-01 against the managed NGINX hosts. Wildcards need DNS-01 and "
                    + "are refused. Requires MANAGE over the namespace of every domain requested, because a "
                    + "certificate is authority to terminate TLS for that name.")
    public ResponseEntity<CertificateDtos.Response> request(
            @Valid @RequestBody CertificateDtos.RequestAcmeRequest request,
            UriComponentsBuilder uriBuilder) {

        Certificate certificate = certificates.requestAcme(new CertificateCommands.RequestAcme(
                request.name(),
                request.domains(),
                request.autoRenew() == null || request.autoRenew(),
                request.renewBeforeDays() == null ? 30 : request.renewBeforeDays()));

        URI location = uriBuilder.path("/api/v1/certificates/{id}").buildAndExpand(certificate.id()).toUri();
        return ResponseEntity.created(location).body(toResponse(certificate));
    }

    @PostMapping("/rewrap-secrets")
    @Operation(summary = "Re-encrypt stored private keys under the current key-encryption key",
            description = "For a key rotation or a change of key provider. Every secret records the "
                    + "key that wrapped it, so keep the previous key or provider configured until this "
                    + "reports nothing left to do — then it can be removed. Resumable: a secret that "
                    + "cannot be read is counted and skipped, not retried forever.")
    public CertificateDtos.RewrapResponse rewrapSecrets() {
        SecretRewrapService.Result result = rewrap.rewrapAll();
        return new CertificateDtos.RewrapResponse(result.examined(), result.rewrapped(), result.failed());
    }

    @PostMapping("/upload")
    @Operation(summary = "Upload certificate material",
            description = "The domains and dates are read from the certificate, not from the request. "
                    + "Uploaded certificates are never renewed automatically; the platform has no relationship "
                    + "with whoever issued them.")
    public ResponseEntity<CertificateDtos.Response> upload(
            @Valid @RequestBody CertificateDtos.UploadRequest request,
            UriComponentsBuilder uriBuilder) {

        Certificate certificate = certificates.upload(new CertificateCommands.Upload(
                request.name(), request.fullChainPem(), request.privateKeyPem()));

        URI location = uriBuilder.path("/api/v1/certificates/{id}").buildAndExpand(certificate.id()).toUri();
        return ResponseEntity.created(location).body(toResponse(certificate));
    }

    @PostMapping("/{id}/renew")
    @Operation(summary = "Renew now",
            description = "Renewal normally happens on its own inside the renewal window. This forces it, "
                    + "and counts against the authority's rate limits like any other request.")
    public CertificateDtos.Response renew(@PathVariable UUID id) {
        return toResponse(certificates.renew(id));
    }

    @PutMapping("/{id}/renewal")
    @Operation(summary = "Configure automatic renewal")
    public CertificateDtos.Response configureRenewal(@PathVariable UUID id,
                                                      @Valid @RequestBody CertificateDtos.ConfigureRenewalRequest request) {
        return toResponse(certificates.configureRenewal(new CertificateCommands.ConfigureRenewal(
                id, request.autoRenewOrDefault(), request.renewBeforeDaysOrDefault())));
    }

    @PostMapping("/{id}/revoke")
    @Operation(summary = "Revoke a certificate",
            description = "Asks the issuing authority to revoke it where that is supported, and marks it "
                    + "unusable here either way. Revoked material is never deployed.")
    public CertificateDtos.Response revoke(@PathVariable UUID id) {
        return toResponse(certificates.revoke(id));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a certificate",
            description = "Refused while any proxy site still references it.")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        certificates.delete(id);
        return ResponseEntity.noContent().build();
    }

    private CertificateDtos.Response toResponse(Certificate certificate) {
        CertificateMetadata metadata = certificate.metadata();
        return new CertificateDtos.Response(
                certificate.id(),
                certificate.name(),
                certificate.provider().name(),
                certificate.domains().stream().sorted().toList(),
                certificate.status().name(),
                metadata == null ? null : metadata.subject(),
                metadata == null ? null : metadata.issuer(),
                metadata == null ? null : metadata.serialNumber(),
                metadata == null ? null : metadata.fingerprintSha256(),
                metadata == null ? null : metadata.notBefore(),
                metadata == null ? null : metadata.notAfter(),
                metadata == null ? null : certificate.daysRemaining(clock.instant()),
                certificate.autoRenew(),
                certificate.renewBeforeDays(),
                repository.isInUse(certificate.id()),
                certificate.lastError(),
                certificate.revokedAt(),
                certificate.createdBy(),
                certificate.createdAt(),
                certificate.version());
    }
}
