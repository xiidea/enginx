package net.xiidea.enginx.infrastructure.acme;

import net.xiidea.enginx.domain.certificate.AcmeChallengePublisher;
import net.xiidea.enginx.domain.certificate.DnsChallengePublisher;
import net.xiidea.enginx.domain.certificate.Certificate;
import net.xiidea.enginx.domain.certificate.CertificateIssuanceException;
import net.xiidea.enginx.domain.certificate.CertificateProvider;
import net.xiidea.enginx.domain.certificate.CertificateProviderKind;
import net.xiidea.enginx.domain.certificate.CertificateRequest;
import net.xiidea.enginx.domain.certificate.IssuedCertificate;
import org.shredzone.acme4j.Authorization;
import org.shredzone.acme4j.Login;
import org.shredzone.acme4j.Order;
import org.shredzone.acme4j.Session;
import org.shredzone.acme4j.Status;
import org.shredzone.acme4j.challenge.Dns01Challenge;
import org.shredzone.acme4j.challenge.Http01Challenge;
import org.shredzone.acme4j.exception.AcmeException;
import org.shredzone.acme4j.exception.AcmeRateLimitedException;
import org.shredzone.acme4j.exception.AcmeServerException;
import org.shredzone.acme4j.util.KeyPairUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringWriter;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;

/**
 * Obtains certificates over ACME (RFC 8555), validating with HTTP-01.
 *
 * <p>HTTP-01 rather than DNS-01 because it needs nothing but the NGINX hosts this platform already
 * controls. The cost is that wildcards are impossible: authorities only issue those against
 * DNS-01. A deployment that needs wildcards will need a DNS provider integration behind the same
 * {@link CertificateProvider} interface, which is why the interface exists.
 */
@Component
@EnableConfigurationProperties(AcmeProperties.class)
public class AcmeCertificateProvider implements CertificateProvider {

    private static final Logger log = LoggerFactory.getLogger(AcmeCertificateProvider.class);

    private final AcmeProperties properties;
    private final AcmeAccountStore accounts;
    private final AcmeChallengePublisher challenges;
    private final DnsChallengePublisher dns;
    /** Use DNS-01 even for non-wildcards. Useful where port 80 is not reachable from outside. */
    private final boolean preferDns;

    public AcmeCertificateProvider(AcmeProperties properties, AcmeAccountStore accounts,
                                   AcmeChallengePublisher challenges,
                                   DnsChallengePublisher dns,
                                   @org.springframework.beans.factory.annotation.Value(
                                           "${enginx.acme.dns.prefer-dns-01:false}") boolean preferDns) {
        this.properties = properties;
        this.accounts = accounts;
        this.challenges = challenges;
        this.dns = dns;
        this.preferDns = preferDns;
    }

    @Override
    public CertificateProviderKind kind() {
        return CertificateProviderKind.ACME;
    }

    @Override
    public IssuedCertificate issue(CertificateRequest request) {
        if (request.hasWildcard() && !dns.isConfigured()) {
            // Fail before contacting the authority. A wildcard order would be rejected after an
            // authorisation was already attempted, spending rate-limit budget to learn something
            // knowable here.
            throw new CertificateIssuanceException(
                    "Wildcard certificates require DNS-01 validation, and no DNS provider is configured. "
                            + "Configure one under enginx.acme.dns, or List the subdomains explicitly "
                            + "instead of using a wildcard.", false);
        }
        return obtain(request.domains().stream().toList());
    }

    @Override
    public IssuedCertificate renew(Certificate certificate) {
        // Exactly the domains already recorded. Renewing with anything else would quietly change
        // what the certificate covers under the sites relying on it.
        return obtain(certificate.domains().stream().toList());
    }

    private IssuedCertificate obtain(List<String> domains) {
        List<Published> published = new ArrayList<>();
        try {
            Session session = new Session(properties.directoryUrl());
            Login login = accounts.login(session);

            Order order = login.getAccount().newOrder().domains(domains).create();
            log.info("ACME order created for {}", domains);

            for (Authorization authorization : order.getAuthorizations()) {
                if (authorization.getStatus() == Status.VALID) {
                    // The account already proved control of this name recently; authorities cache
                    // authorisations, and re-validating would be wasted work.
                    continue;
                }
                published.add(satisfy(authorization));
            }

            KeyPair domainKey = KeyPairUtils.createKeyPair(properties.keySize());
            order.execute(domainKey);

            Status status = order.waitForCompletion(properties.orderTimeout());
            if (status != Status.VALID) {
                throw new CertificateIssuanceException(
                        "The authority did not issue the certificate: order finished as " + status
                                + describe(order.getError().map(Object::toString).orElse(null)), false);
            }

            return IssuedCertificate.of(chainPem(order), keyPem(domainKey));

        } catch (AcmeRateLimitedException e) {
            // Never retried automatically. Let's Encrypt counts these per week, and retrying is
            // how an account loses the ability to issue anything at all for days.
            throw new CertificateIssuanceException(
                    "The authority is rate limiting this account: " + e.getMessage(), e, false);
        } catch (AcmeServerException e) {
            throw new CertificateIssuanceException("The authority rejected the request: " + e.getMessage(), e, false);
        } catch (AcmeException | IOException e) {
            // Transport or availability problems are worth another attempt later.
            throw new CertificateIssuanceException("Could not reach the certificate authority: " + e.getMessage(),
                    e, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CertificateIssuanceException("Interrupted while waiting for the authority", e, true);
        } finally {
            // Always, including on failure: a stale token left on every host is a small but
            // permanent leak of what the platform was recently asked to prove.
            published.forEach(entry -> {
                try {
                    entry.withdraw(challenges, dns);
                } catch (RuntimeException e) {
                    log.warn("Could not withdraw ACME challenge {}", entry.key(), e);
                }
            });
        }
    }

    /**
     * Proves control of one identifier, and waits for the authority to agree.
     *
     * <p>DNS-01 is used when the identifier is a wildcard — no authority will accept anything else
     * for one — and otherwise HTTP-01, which needs nothing but the NGINX hosts this platform
     * already manages. Choosing per authorization rather than per order matters: an order mixing
     * {@code example.com} and {@code *.example.com} validates the first over HTTP and the second
     * over DNS, and forcing both down one path would fail an order that should succeed.
     *
     * @return what was published, so the caller can withdraw it afterwards
     */
    private Published satisfy(Authorization authorization) throws AcmeException, InterruptedException {
        String identifier = authorization.getIdentifier().getDomain();

        // Decided by what the authority offers, not by inspecting the identifier for a wildcard.
        // RFC 8555 reports a wildcard authorization under its *base* domain with a separate flag,
        // so `*.example.com` arrives here calling itself `example.com` — and a check for the
        // prefix silently routes it to HTTP-01, which no authority offers for a wildcard. Asking
        // what is on the table is both simpler and impossible to get subtly wrong.
        boolean httpOffered = authorization.findChallenge(Http01Challenge.class).isPresent();
        boolean dnsOffered = authorization.findChallenge(Dns01Challenge.class).isPresent();

        if (dnsOffered && (preferDns || !httpOffered)) {
            if (!dns.isConfigured()) {
                throw new CertificateIssuanceException(
                        "The authority requires DNS-01 validation for " + identifier
                                + " — which is always the case for a wildcard — and no DNS provider is "
                                + "configured. Configure one under enginx.acme.dns.", false);
            }
            return satisfyWithDns(authorization, identifier);
        }
        if (httpOffered) {
            return satisfyWithHttp(authorization, identifier);
        }
        throw new CertificateIssuanceException(
                "The authority offered no validation method this platform supports for " + identifier, false);
    }

    private Published satisfyWithHttp(Authorization authorization, String identifier)
            throws AcmeException, InterruptedException {

        Http01Challenge challenge = authorization.findChallenge(Http01Challenge.class)
                .orElseThrow(() -> new CertificateIssuanceException(
                        "The authority did not offer HTTP-01 validation for " + identifier, false));

        challenges.publish(challenge.getToken(), challenge.getAuthorization());
        challenge.trigger();

        await(authorization, identifier, challenge.getError().map(Object::toString).orElse(null),
                "Check that the domain resolves to a managed NGINX host and that port 80 is reachable.");
        return Published.http(challenge.getToken());
    }

    private Published satisfyWithDns(Authorization authorization, String identifier)
            throws AcmeException, InterruptedException {

        Dns01Challenge challenge = authorization.findChallenge(Dns01Challenge.class)
                .orElseThrow(() -> new CertificateIssuanceException(
                        "The authority did not offer DNS-01 validation for " + identifier, false));

        // acme4j derives the record name, which strips a wildcard prefix: the challenge for
        // *.example.com is published at _acme-challenge.example.com, the same place as for the
        // bare name. Deriving it here instead is a well-known way to get wildcards subtly wrong.
        String recordName = challenge.getRRName(authorization.getIdentifier());

        dns.publish(recordName, challenge.getDigest());
        challenge.trigger();

        await(authorization, identifier, challenge.getError().map(Object::toString).orElse(null),
                "Check that the TXT record at " + recordName + " is visible to public resolvers.");
        return Published.dns(recordName);
    }

    private void await(Authorization authorization, String identifier, String error, String hint)
            throws AcmeException, InterruptedException {

        Status status = authorization.waitForCompletion(properties.challengeTimeout());
        if (status != Status.VALID) {
            throw new CertificateIssuanceException(
                    "Validation failed for " + identifier + ": " + status + describe(error) + ". " + hint, false);
        }
    }

    /**
     * One published challenge response, remembered so it can be withdrawn.
     *
     * <p>Carries which mechanism published it: a token and a record name are both strings, and
     * withdrawing one through the other's publisher would silently leave the real record in place.
     */
    private record Published(boolean viaDns, String key) {

        static Published http(String token) {
            return new Published(false, token);
        }

        static Published dns(String recordName) {
            return new Published(true, recordName);
        }

        void withdraw(AcmeChallengePublisher http, DnsChallengePublisher dns) {
            if (viaDns) {
                dns.withdraw(key);
            } else {
                http.withdraw(key);
            }
        }
    }

    @Override
    public boolean supportsRevocation() {
        return true;
    }

    @Override
    public void revoke(Certificate certificate, String privateKeyPem) {
        try {
            Session session = new Session(properties.directoryUrl());
            Login login = accounts.login(session);

            java.security.cert.X509Certificate leaf = (java.security.cert.X509Certificate)
                    java.security.cert.CertificateFactory.getInstance("X.509")
                            .generateCertificate(new java.io.ByteArrayInputStream(
                                    certificate.fullChainPem().getBytes(java.nio.charset.StandardCharsets.UTF_8)));

            org.shredzone.acme4j.Certificate.revoke(login, leaf,
                    org.shredzone.acme4j.RevocationReason.CESSATION_OF_OPERATION);
        } catch (AcmeException | java.security.cert.CertificateException e) {
            throw new CertificateIssuanceException("Could not revoke the certificate: " + e.getMessage(), e, false);
        }
    }

    private static String chainPem(Order order) throws IOException {
        StringWriter writer = new StringWriter();
        // Writes the leaf followed by the intermediates, which is the order NGINX requires.
        order.getCertificate().writeCertificate(writer);
        return writer.toString();
    }

    private static String keyPem(KeyPair keyPair) throws IOException {
        StringWriter writer = new StringWriter();
        KeyPairUtils.writeKeyPair(keyPair, writer);
        return writer.toString();
    }

    private static String describe(String problem) {
        return problem == null ? "" : " (" + problem + ")";
    }
}
