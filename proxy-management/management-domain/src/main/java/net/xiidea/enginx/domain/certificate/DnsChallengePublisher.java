package net.xiidea.enginx.domain.certificate;

/**
 * Publishes a DNS-01 challenge response as a TXT record.
 *
 * <p>The only validation method an authority will accept for a wildcard, because proving control
 * of {@code *.example.com} means proving control of the zone rather than of any one host.
 *
 * <p>A port, and a narrow one: the record name is derived by the ACME client and the value is
 * opaque, so an implementation needs no knowledge of certificates at all — only of how to write a
 * TXT record in whatever zone a deployment happens to use.
 */
public interface DnsChallengePublisher {

    /** Whether a DNS provider is configured. False means wildcards cannot be issued. */
    boolean isConfigured();

    /**
     * Writes the record and returns once it is expected to be visible.
     *
     * <p>Implementations are responsible for their own propagation delay. An authority that
     * queries before the record has propagated records a failed validation, and failed
     * validations count against rate limits.
     *
     * @param recordName fully qualified, e.g. {@code _acme-challenge.example.com}
     */
    void publish(String recordName, String value);

    /** Removes the record once validation has finished, successfully or not. */
    void withdraw(String recordName);
}
