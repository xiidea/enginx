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

    public record Create(ProxySiteSpec spec, AdminState adminState) {
    }

    /**
     * @param expectedVersion the version the caller last saw. When present it must still match,
     *                        otherwise a concurrent edit has happened and the write is refused.
     */
    public record Update(UUID id, ProxySiteSpec spec, Long expectedVersion) {
    }

    public record Renew(UUID id, Instant expiresAt, Long expectedVersion) {
    }

    public record Clone(UUID sourceId, String name, DomainName domain) {
    }
}
