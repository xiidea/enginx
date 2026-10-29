package net.xiidea.enginx.application.gate;

import java.util.Optional;
import java.util.UUID;

/**
 * The per-site key a gated site's gate signs with.
 *
 * <p>One key, two uses, kept apart by derivation ({@link GateKeyDerivation}): visitors' session
 * cookies, and the gate's requests to have a code mailed. Generated here, sealed at rest, opened
 * only to assemble a bundle or to check a relay request.
 */
public interface GateKeys {

    /** Issues a key for the site if it has none. Idempotent. */
    void ensure(UUID siteId);

    /** Replaces the site's key, which invalidates every session signed with the old one. */
    void rotate(UUID siteId);

    /** The site's raw 32-byte key, or empty when none has been issued. */
    Optional<byte[]> reveal(UUID siteId);
}
