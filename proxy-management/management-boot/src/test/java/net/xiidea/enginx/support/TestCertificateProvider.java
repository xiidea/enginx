package net.xiidea.enginx.support;

import net.xiidea.enginx.domain.certificate.Certificate;
import net.xiidea.enginx.domain.certificate.CertificateIssuanceException;
import net.xiidea.enginx.domain.certificate.CertificateProvider;
import net.xiidea.enginx.domain.certificate.CertificateProviderKind;
import net.xiidea.enginx.domain.certificate.CertificateRequest;
import net.xiidea.enginx.domain.certificate.IssuedCertificate;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.io.StringWriter;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Issues real, self-signed X.509 certificates without contacting an authority.
 *
 * <p>Real material rather than a stub string, because everything downstream parses it: metadata
 * extraction, SAN-based domain recording, expiry monitoring and the renderer all operate on
 * genuine certificates. A fake would test the tests.
 */
public class TestCertificateProvider implements CertificateProvider {

    private final AtomicInteger issued = new AtomicInteger();
    private volatile Duration lifetime = Duration.ofDays(90);
    private volatile boolean failing;
    private volatile boolean revokeCalled;

    public void issuesCertificatesValidFor(Duration duration) {
        this.lifetime = duration;
    }

    public void fail(boolean shouldFail) {
        this.failing = shouldFail;
    }

    public int issuedCount() {
        return issued.get();
    }

    public boolean revokeCalled() {
        return revokeCalled;
    }

    public void reset() {
        issued.set(0);
        lifetime = Duration.ofDays(90);
        failing = false;
        revokeCalled = false;
    }

    @Override
    public CertificateProviderKind kind() {
        return CertificateProviderKind.ACME;
    }

    @Override
    public IssuedCertificate issue(CertificateRequest request) {
        return generate(request.domains());
    }

    @Override
    public IssuedCertificate renew(Certificate certificate) {
        return generate(certificate.domains());
    }

    private IssuedCertificate generate(Collection<String> domains) {
        if (failing) {
            throw new CertificateIssuanceException("the authority refused the request", false);
        }
        issued.incrementAndGet();
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();

            Instant now = Instant.now();
            X500Name subject = new X500Name("CN=" + domains.iterator().next());

            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                    subject,
                    new BigInteger(64, new SecureRandom()),
                    Date.from(now.minus(Duration.ofMinutes(1))),
                    Date.from(now.plus(lifetime)),
                    subject,
                    keyPair.getPublic());

            GeneralName[] names = domains.stream()
                    .map(domain -> new GeneralName(GeneralName.dNSName, domain))
                    .toArray(GeneralName[]::new);
            builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(names));

            X509Certificate certificate = new JcaX509CertificateConverter().getCertificate(
                    builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(keyPair.getPrivate())));

            return IssuedCertificate.of(toPem(certificate), toPem(keyPair.getPrivate()));
        } catch (Exception e) {
            throw new CertificateIssuanceException("test provider failed: " + e.getMessage(), e, false);
        }
    }

    private static String toPem(Object object) throws Exception {
        StringWriter writer = new StringWriter();
        try (JcaPEMWriter pem = new JcaPEMWriter(writer)) {
            pem.writeObject(object);
        }
        return writer.toString();
    }

    @Override
    public boolean supportsRevocation() {
        return true;
    }

    @Override
    public void revoke(Certificate certificate, String privateKeyPem) {
        revokeCalled = true;
    }

    @TestConfiguration
    public static class Config {

        @Bean
        @Primary
        public TestCertificateProvider testCertificateProvider() {
            return new TestCertificateProvider();
        }

        /**
         * Replaces the provider list the service builds. Without this the real ACME provider is
         * still registered under the same kind and would win or lose arbitrarily.
         */
        @Bean
        @Primary
        public List<CertificateProvider> certificateProviders(TestCertificateProvider acme,
                                                              net.xiidea.enginx.infrastructure.certificate.ManualCertificateProvider manual) {
            return List.of(acme, manual);
        }
    }
}
