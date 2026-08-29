package net.xiidea.enginx.domain.nginx;

import net.xiidea.enginx.domain.shared.ValidationException;

import java.net.URI;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A managed NGINX host, reached through its agent.
 *
 * <p>Two ways round, and the aggregate holds which one applies. A {@link ConnectivityMode#PUSH}
 * host is dialled at {@code agentBaseUrl}, and its certificate fingerprint is pinned here rather
 * than trusted through the CA alone: a CA-signed certificate proves only that some agent is
 * speaking, not that it is <em>this</em> host (risk R1 of the agent protocol). A
 * {@link ConnectivityMode#PULL} host is never dialled, so it has neither field — it authenticates
 * itself when it calls in.
 *
 * <p>The two field sets are mutually exclusive, and enforced as such in both directions. A row
 * carrying a URL it will never be dialled at, or lacking one it needs, describes a host nobody
 * can reach.
 */
public final class NginxInstance {

    private static final Pattern SHA256_FINGERPRINT = Pattern.compile("^[A-Fa-f0-9]{64}$");
    private static final Pattern NAME = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?$");

    private final UUID id;
    private String name;
    private String hostname;
    private URI agentBaseUrl;
    private String agentCertFingerprint;
    private final ConnectivityMode connectivityMode;
    private String environment;
    private InstanceStatus status;
    private String nginxVersion;
    private String agentVersion;
    private Instant lastSeenAt;
    private final Instant createdAt;
    private Instant updatedAt;
    private final long version;

    private NginxInstance(UUID id, String name, String hostname, URI agentBaseUrl, String agentCertFingerprint,
                          ConnectivityMode connectivityMode, String environment, InstanceStatus status,
                          String nginxVersion, String agentVersion,
                          Instant lastSeenAt, Instant createdAt, Instant updatedAt, long version) {
        this.id = id;
        this.name = name;
        this.hostname = hostname;
        this.agentBaseUrl = agentBaseUrl;
        this.agentCertFingerprint = agentCertFingerprint;
        this.connectivityMode = connectivityMode == null ? ConnectivityMode.PUSH : connectivityMode;
        this.environment = environment;
        this.status = status;
        this.nginxVersion = nginxVersion;
        this.agentVersion = agentVersion;
        this.lastSeenAt = lastSeenAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    /** A host the platform will dial. Needs a URL to dial and a certificate to pin. */
    public static NginxInstance register(UUID id, String name, String hostname, String agentBaseUrl,
                                         String agentCertFingerprint, String environment, Instant now) {
        return new NginxInstance(id,
                validName(name),
                validHostname(hostname),
                validAgentUrl(agentBaseUrl),
                validFingerprint(agentCertFingerprint),
                ConnectivityMode.PUSH,
                normalisedEnvironment(environment),
                InstanceStatus.UNKNOWN, null, null, null, now, now, 0L);
    }

    /**
     * A host that will dial the platform, created by the host itself as it enrols.
     *
     * <p>No URL and no fingerprint, and not merely because they are unknown: accepting either
     * would leave a field that looks like it means something and does not. What identifies this
     * host is the token it presents, which the enrolment service issues alongside this.
     */
    public static NginxInstance registerPull(UUID id, String name, String hostname, String environment,
                                             Instant now) {
        return new NginxInstance(id,
                validName(name),
                validHostname(hostname),
                null,
                null,
                ConnectivityMode.PULL,
                normalisedEnvironment(environment),
                InstanceStatus.UNKNOWN, null, null, null, now, now, 0L);
    }

    private static String normalisedEnvironment(String environment) {
        return environment == null || environment.isBlank()
                ? "PRODUCTION"
                : environment.trim().toUpperCase(Locale.ROOT);
    }

    public static NginxInstance rehydrate(UUID id, String name, String hostname, URI agentBaseUrl,
                                          String agentCertFingerprint, ConnectivityMode connectivityMode,
                                          String environment, InstanceStatus status,
                                          String nginxVersion, String agentVersion, Instant lastSeenAt,
                                          Instant createdAt, Instant updatedAt, long version) {
        return new NginxInstance(id, name, hostname, agentBaseUrl, agentCertFingerprint, connectivityMode,
                environment, status, nginxVersion, agentVersion, lastSeenAt, createdAt, updatedAt, version);
    }

    private static String validName(String name) {
        if (name == null || !NAME.matcher(name.trim().toLowerCase(Locale.ROOT)).matches()) {
            throw new ValidationException("name",
                    "Instance name must be lowercase alphanumeric with hyphens, for example nginx-prod-01");
        }
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private static String validHostname(String hostname) {
        if (hostname == null || hostname.isBlank()) {
            throw new ValidationException("hostname", "Hostname must not be blank");
        }
        return hostname.trim().toLowerCase(Locale.ROOT);
    }

    private static URI validAgentUrl(String agentBaseUrl) {
        if (agentBaseUrl == null || agentBaseUrl.isBlank()) {
            throw new ValidationException("agentBaseUrl", "Agent base URL must not be blank");
        }
        URI uri;
        try {
            uri = URI.create(agentBaseUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new ValidationException("agentBaseUrl", "Agent base URL is not a valid URI");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new ValidationException("agentBaseUrl", "The agent must be reached over HTTPS with mTLS");
        }
        if (uri.getHost() == null) {
            throw new ValidationException("agentBaseUrl", "Agent base URL must include a host");
        }
        return uri;
    }

    private static String validFingerprint(String fingerprint) {
        if (fingerprint == null) {
            throw new ValidationException("agentCertFingerprint", "The agent certificate fingerprint is required");
        }
        String normalised = fingerprint.replace(":", "").replace(" ", "").trim().toUpperCase(Locale.ROOT);
        if (!SHA256_FINGERPRINT.matcher(normalised).matches()) {
            throw new ValidationException("agentCertFingerprint",
                    "The fingerprint must be a SHA-256 digest: 64 hexadecimal characters");
        }
        return normalised;
    }

    /**
     * Trusts a new agent certificate.
     *
     * <p>The fingerprint is pinned in addition to CA verification, so replacing an agent's
     * certificate makes the host unreachable until this is called — deliberately, since accepting
     * any certificate the CA ever signed is exactly what pinning exists to prevent.
     *
     * <p>There is a window either way round: update first and calls fail until the agent swaps,
     * swap first and they fail until this is called. That is a control-plane outage of seconds and
     * not a traffic one — the host keeps serving throughout, it simply cannot be changed.
     */
    public void agentCertificateRotated(String newFingerprint, Instant now) {
        if (connectivityMode.isPull()) {
            throw new ValidationException("agentCertFingerprint",
                    "This host connects to the platform rather than being dialled, so it has no "
                            + "certificate to pin. Reissue its agent token instead.");
        }
        String normalised = validFingerprint(newFingerprint);
        if (normalised.equals(this.agentCertFingerprint)) {
            throw new ValidationException("agentCertFingerprint",
                    "That is already the trusted fingerprint for this instance");
        }
        this.agentCertFingerprint = normalised;
        // Reset to UNKNOWN rather than left as it was: what the platform knew about this host was
        // learned through a certificate it no longer trusts, and the next heartbeat re-establishes
        // it. Claiming ONLINE on the strength of a superseded identity would be a small lie.
        this.status = InstanceStatus.UNKNOWN;
        this.updatedAt = now;
    }

    public void observed(InstanceStatus newStatus, String nginxVersion, String agentVersion, Instant now) {
        this.status = newStatus;
        this.nginxVersion = nginxVersion;
        this.agentVersion = agentVersion;
        this.lastSeenAt = now;
        this.updatedAt = now;
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String hostname() {
        return hostname;
    }

    public URI agentBaseUrl() {
        return agentBaseUrl;
    }

    public String agentCertFingerprint() {
        return agentCertFingerprint;
    }

    public ConnectivityMode connectivityMode() {
        return connectivityMode;
    }

    public String environment() {
        return environment;
    }

    public InstanceStatus status() {
        return status;
    }

    public String nginxVersion() {
        return nginxVersion;
    }

    public String agentVersion() {
        return agentVersion;
    }

    public Instant lastSeenAt() {
        return lastSeenAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public long version() {
        return version;
    }
}
