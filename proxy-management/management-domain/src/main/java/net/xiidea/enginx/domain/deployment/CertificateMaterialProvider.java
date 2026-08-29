package net.xiidea.enginx.domain.deployment;

import java.util.Optional;
import java.util.UUID;

/**
 * Supplies the certificate files a bundle needs.
 *
 * <p>A port rather than a direct dependency because issuing and storing certificates is Phase 6.
 * Until then the implementation returns nothing, and a site with SSL enabled fails to render with
 * a clear message instead of producing a configuration that NGINX would refuse to load.
 */
public interface CertificateMaterialProvider {

    Optional<RenderedCertificate> materialFor(UUID certificateId);
}
