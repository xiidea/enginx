package net.xiidea.enginx.domain.nginx;

import net.xiidea.enginx.domain.certificate.EncryptedSecret;
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
 *
 * <p>A push host proves itself one of two ways, set by its {@link PushTransport}: a pinned
 * certificate, or a pre-shared token. Never both, so neither can be mistaken for the one in use.
 * The token is held only in sealed form; the plaintext exists at registration and at the moment
 * of the call, and nowhere in between.
 */
public final class NginxInstance {

    private static final Pattern SHA256_FINGERPRINT = Pattern.compile("^[A-Fa-f0-9]{64}$");
    private static final Pattern NAME = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?$");

    /**
     * The shortest agent token accepted. Matches the agent, which refuses to start with a shorter
     * one, so a token registered here is always one an agent could actually be configured with.
     */
    public static final int MIN_AGENT_TOKEN_LENGTH = 32;

    private final UUID id;
    private String name;
    private String hostname;
    private URI agentBaseUrl;
    private String agentCertFingerprint;
    private final ConnectivityMode connectivityMode;
    private PushTransport pushTransport;
    private EncryptedSecret agentToken;
    private String environment;
    private InstanceStatus status;
    private String nginxVersion;
    private String agentVersion;
    private Instant lastSeenAt;
    private final Instant createdAt;
    private Instant updatedAt;
    private final long version;
    /**
     * Whether bundles carry the platform's catch-all servers. True unless the host keeps its own
     * default server: two {@code default_server} listeners on one port fail validation.
     */
    private boolean defaultServerManaged = true;

    private NginxInstance(UUID id, String name, String hostname, URI agentBaseUrl, String agentCertFingerprint,
                          ConnectivityMode connectivityMode, PushTransport pushTransport, EncryptedSecret agentToken,
                          String environment, InstanceStatus status,
                          String nginxVersion, String agentVersion,
                          Instant lastSeenAt, Instant createdAt, Instant updatedAt, long version) {
        this.id = id;
        this.name = name;
        this.hostname = hostname;
        this.agentBaseUrl = agentBaseUrl;
        this.agentCertFingerprint = agentCertFingerprint;
        this.connectivityMode = connectivityMode == null ? ConnectivityMode.PUSH : connectivityMode;
        this.pushTransport = pushTransport == null ? PushTransport.MTLS : pushTransport;
        this.agentToken = agentToken;
        this.environment = environment;
        this.status = status;
        this.nginxVersion = nginxVersion;
        this.agentVersion = agentVersion;
        this.lastSeenAt = lastSeenAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    /** A host the platform will dial over mTLS. Needs a URL to dial and a certificate to pin. */
    public static NginxInstance register(UUID id, String name, String hostname, String agentBaseUrl,
                                         String agentCertFingerprint, String environment, Instant now) {
        return registerPush(id, name, hostname, agentBaseUrl, PushTransport.MTLS, agentCertFingerprint, null, environment, now);
    }

    /**
     * A host the platform will dial.
     *
     * @param agentCertFingerprint required for {@link PushTransport#MTLS}, refused otherwise
     * @param agentToken           the sealed token, required for {@link PushTransport#HTTP_TOKEN} and
     *                             refused otherwise. Check the plaintext with {@link #validAgentToken}
     *                             before sealing it
     */
    public static NginxInstance registerPush(UUID id, String name, String hostname, String agentBaseUrl,
                                             PushTransport pushTransport, String agentCertFingerprint,
                                             EncryptedSecret agentToken, String environment, Instant now) {
        PushTransport transport = pushTransport == null ? PushTransport.MTLS : pushTransport;
        URI url = validAgentUrl(agentBaseUrl, transport);
        String fingerprint = null;
        if (transport.isToken()) {
            if (agentCertFingerprint != null && !agentCertFingerprint.isBlank()) {
                throw new ValidationException("agentCertFingerprint",
                        "A host that authenticates with a token has no certificate to pin");
            }
            if (agentToken == null) {
                throw new ValidationException("agentAuthToken", "An agent token is required for HTTP_TOKEN");
            }
        } else {
            if (agentToken != null) {
                throw new ValidationException("agentAuthToken",
                        "A host that authenticates with a certificate takes no token");
            }
            fingerprint = validFingerprint(agentCertFingerprint);
        }

        return new NginxInstance(id,
                validName(name),
                validHostname(hostname),
                url,
                fingerprint,
                ConnectivityMode.PUSH,
                transport,
                agentToken,
                normalisedEnvironment(environment),
                InstanceStatus.UNKNOWN, null, null, null, now, now, 0L);
    }

    /**
     * Checks a plaintext agent token, before it is sealed and never seen again.
     *
     * @return the token, trimmed
     */
    public static String validAgentToken(String token) {
        if (token == null || token.isBlank()) {
            throw new ValidationException("agentAuthToken", "An agent token is required for HTTP_TOKEN");
        }
        String trimmed = token.trim();
        if (trimmed.length() < MIN_AGENT_TOKEN_LENGTH) {
            throw new ValidationException("agentAuthToken", "The agent token must be at least "
                    + MIN_AGENT_TOKEN_LENGTH + " characters. Generate one with: openssl rand -hex 32");
        }
        return trimmed;
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
                PushTransport.MTLS,
                null,
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
                                          PushTransport pushTransport, EncryptedSecret agentToken,
                                          String environment, InstanceStatus status,
                                          String nginxVersion, String agentVersion, Instant lastSeenAt,
                                          Instant createdAt, Instant updatedAt, long version) {
        return new NginxInstance(id, name, hostname, agentBaseUrl, agentCertFingerprint, connectivityMode,
                pushTransport == null ? PushTransport.MTLS : pushTransport, agentToken,
                environment, status, nginxVersion, agentVersion, lastSeenAt, createdAt, updatedAt, version);
    }

    public static NginxInstance rehydrate(UUID id, String name, String hostname, URI agentBaseUrl,
                                          String agentCertFingerprint, ConnectivityMode connectivityMode,
                                          PushTransport pushTransport, EncryptedSecret agentToken,
                                          String environment, InstanceStatus status,
                                          String nginxVersion, String agentVersion, Instant lastSeenAt,
                                          Instant createdAt, Instant updatedAt, long version,
                                          boolean defaultServerManaged) {
        NginxInstance instance = rehydrate(id, name, hostname, agentBaseUrl, agentCertFingerprint,
                connectivityMode, pushTransport, agentToken, environment, status, nginxVersion,
                agentVersion, lastSeenAt, createdAt, updatedAt, version);
        instance.defaultServerManaged = defaultServerManaged;
        return instance;
    }

    public static NginxInstance rehydrate(UUID id, String name, String hostname, URI agentBaseUrl,
                                          String agentCertFingerprint, ConnectivityMode connectivityMode,
                                          String environment, InstanceStatus status,
                                          String nginxVersion, String agentVersion, Instant lastSeenAt,
                                          Instant createdAt, Instant updatedAt, long version) {
        return rehydrate(id, name, hostname, agentBaseUrl, agentCertFingerprint, connectivityMode,
                PushTransport.MTLS, null, environment, status, nginxVersion, agentVersion,
                lastSeenAt, createdAt, updatedAt, version);
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

    private static URI validAgentUrl(String agentBaseUrl, PushTransport transport) {
        if (agentBaseUrl == null || agentBaseUrl.isBlank()) {
            throw new ValidationException("agentBaseUrl", "Agent base URL must not be blank");
        }
        URI uri;
        try {
            uri = URI.create(agentBaseUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new ValidationException("agentBaseUrl", "Agent base URL is not a valid URI");
        }
        String scheme = uri.getScheme();
        if (!transport.isToken() && !"https".equalsIgnoreCase(scheme)) {
            throw new ValidationException("agentBaseUrl", "The agent must be reached over HTTPS with mTLS");
        }
        // Plain HTTP is accepted for a token host: it may sit behind a proxy that terminates TLS
        // on the same machine. The console warns, because without that proxy the token and every
        // bundle, private keys included, cross the network in clear.
        if (transport.isToken() && !"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
            throw new ValidationException("agentBaseUrl", "The agent must be reached over HTTP or HTTPS");
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
        if (pushTransport.isToken()) {
            throw new ValidationException("agentCertFingerprint",
                    "This host authenticates with a token, so it has no certificate to pin. "
                            + "Rotate its token instead.");
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

    /**
     * Trusts a new token for a host the platform dials with one.
     *
     * <p>The same window as a certificate rotation, and the same order works: set the new token on
     * the host, then here. Calls fail in between; traffic does not.
     */
    public void agentTokenRotated(EncryptedSecret newToken, Instant now) {
        if (connectivityMode.isPull() || !pushTransport.isToken()) {
            throw new ValidationException("agentAuthToken",
                    "This host does not authenticate the platform with a token");
        }
        if (newToken == null) {
            throw new ValidationException("agentAuthToken", "An agent token is required");
        }
        this.agentToken = newToken;
        this.status = InstanceStatus.UNKNOWN;
        this.updatedAt = now;
    }

    /**
     * Decides who answers names no site matches: the platform's catch-all, or a default server the
     * host already has. Takes effect at the next deployment.
     */
    public void defaultServerManaged(boolean managed, Instant now) {
        this.defaultServerManaged = managed;
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

    public boolean defaultServerManaged() {
        return defaultServerManaged;
    }

    public ConnectivityMode connectivityMode() {
        return connectivityMode;
    }

    public PushTransport pushTransport() {
        return pushTransport;
    }

    /** Sealed. Null unless {@link PushTransport#HTTP_TOKEN}. */
    public EncryptedSecret agentToken() {
        return agentToken;
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
