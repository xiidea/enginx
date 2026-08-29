package net.xiidea.enginx.domain.deployment;

import java.util.UUID;

/**
 * Certificate material for one site, as it will be written into the bundle.
 *
 * <p>The private key travels only as far as the bundle and is marked sensitive from the moment it
 * is created, so no code path can log it or return it through the API by accident.
 */
public record RenderedCertificate(UUID certificateId, String fullChainPem, String privateKeyPem) {
}
