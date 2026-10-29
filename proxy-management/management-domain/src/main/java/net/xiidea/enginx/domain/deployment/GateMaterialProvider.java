package net.xiidea.enginx.domain.deployment;

import java.util.Optional;
import java.util.UUID;

/**
 * Supplies what the host's gate needs to protect a site: the site's key, and where to send a
 * one-time code for mailing.
 *
 * <p>The key is secret, decrypted only while a bundle is assembled, the way a certificate's private
 * key is. The relay URL is the management plane's own address, configured once for the platform.
 */
public interface GateMaterialProvider {

    /** For renders with no gated sites, and the tests that need nothing more. */
    GateMaterialProvider NONE = new GateMaterialProvider() {
        @Override
        public Optional<String> keyFor(UUID siteId) {
            return Optional.empty();
        }

        @Override
        public Optional<String> relayUrl() {
            return Optional.empty();
        }
    };

    /** The site's 32-byte gate key, base64-encoded, or empty when none has been issued. */
    Optional<String> keyFor(UUID siteId);

    /** Where the gate posts a code for the platform to mail, or empty when it is not configured. */
    Optional<String> relayUrl();
}
