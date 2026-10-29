package net.xiidea.enginx.application.proxy;

import net.xiidea.enginx.domain.proxy.AdminState;
import net.xiidea.enginx.domain.proxy.ProxySiteSpec;
import net.xiidea.enginx.domain.shared.DomainName;

import java.time.Instant;
import java.util.UUID;

/** Input records for the proxy site use cases. */
public final class ProxySiteCommands {

    private ProxySiteCommands() {
    }

    /**
     * @param protection the protection to apply, with passwords still in the clear; null leaves
     *                   whatever the spec carries
     */
    public record Create(ProxySiteSpec spec, AdminState adminState, ProtectionInput protection) {

        public Create(ProxySiteSpec spec, AdminState adminState) {
            this(spec, adminState, null);
        }
    }

    /**
     * @param expectedVersion the version the caller last saw. When present it must still match,
     *                        otherwise a concurrent edit has happened and the write is refused.
     * @param protection      the protection to apply; null keeps the site's current protection, so
     *                        a client that predates protection cannot remove it by omission
     */
    public record Update(UUID id, ProxySiteSpec spec, Long expectedVersion, ProtectionInput protection) {

        public Update(UUID id, ProxySiteSpec spec, Long expectedVersion) {
            this(id, spec, expectedVersion, null);
        }
    }

    public record Renew(UUID id, Instant expiresAt, Long expectedVersion) {
    }

    public record Clone(UUID sourceId, String name, DomainName domain) {
    }
}
