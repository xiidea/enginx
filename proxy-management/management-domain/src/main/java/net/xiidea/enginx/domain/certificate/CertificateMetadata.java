package net.xiidea.enginx.domain.certificate;

import net.xiidea.enginx.domain.shared.ValidationException;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What a certificate says about itself.
 *
 * <p>Read from the certificate rather than taken from whoever supplied it. An operator uploading
 * material can mistype an expiry, and an ACME response could in principle disagree with the
 * certificate it delivered; either way the platform's monitoring must be driven by the bytes that
 * NGINX will actually serve.
 */
public record CertificateMetadata(
        String subject,
        String issuer,
        String serialNumber,
        String fingerprintSha256,
        Instant notBefore,
        Instant notAfter,
        Set<String> domains) {

    public static CertificateMetadata parse(String pem) {
        X509Certificate leaf = leafOf(pem);
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return new CertificateMetadata(
                    leaf.getSubjectX500Principal().getName(),
                    leaf.getIssuerX500Principal().getName(),
                    leaf.getSerialNumber().toString(16).toUpperCase(Locale.ROOT),
                    HexFormat.of().formatHex(sha256.digest(leaf.getEncoded())).toUpperCase(Locale.ROOT),
                    leaf.getNotBefore().toInstant(),
                    leaf.getNotAfter().toInstant(),
                    subjectAlternativeNames(leaf));
        } catch (CertificateEncodingException | java.security.NoSuchAlgorithmException e) {
            throw new ValidationException("certificatePem", "The certificate could not be read: " + e.getMessage());
        }
    }

    /**
     * The first certificate in the chain.
     *
     * <p>A full chain arrives leaf-first by convention and NGINX requires that order, so anything
     * else is a malformed bundle rather than something to be helpfully reordered.
     */
    private static X509Certificate leafOf(String pem) {
        if (pem == null || pem.isBlank()) {
            throw new ValidationException("certificatePem", "Certificate material must not be blank");
        }
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            Collection<? extends java.security.cert.Certificate> chain =
                    factory.generateCertificates(new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));
            if (chain.isEmpty()) {
                throw new ValidationException("certificatePem", "No certificate found in the supplied PEM");
            }
            return (X509Certificate) chain.iterator().next();
        } catch (java.security.cert.CertificateException e) {
            throw new ValidationException("certificatePem", "The supplied PEM is not a valid certificate");
        }
    }

    /**
     * DNS names from the SAN extension.
     *
     * <p>The common name is deliberately ignored. Browsers stopped honouring it years ago, so a
     * certificate whose CN covers a domain its SAN does not would appear to work here and fail in
     * every client.
     */
    private static Set<String> subjectAlternativeNames(X509Certificate certificate) {
        Set<String> names = new LinkedHashSet<>();
        try {
            Collection<List<?>> alternatives = certificate.getSubjectAlternativeNames();
            if (alternatives == null) {
                return names;
            }
            for (List<?> entry : new ArrayList<>(alternatives)) {
                // Type 2 is dNSName in RFC 5280.
                if (entry.size() >= 2 && Integer.valueOf(2).equals(entry.get(0))) {
                    names.add(String.valueOf(entry.get(1)).toLowerCase(Locale.ROOT));
                }
            }
        } catch (java.security.cert.CertificateParsingException e) {
            throw new ValidationException("certificatePem", "The certificate's subject alternative names are malformed");
        }
        return names;
    }

    /** Whether this certificate covers a domain, honouring a single leading wildcard label. */
    public boolean covers(String domain) {
        String candidate = domain.toLowerCase(Locale.ROOT);
        for (String name : domains) {
            if (name.equals(candidate)) {
                return true;
            }
            if (name.startsWith("*.")) {
                String suffix = name.substring(1);
                // A wildcard matches exactly one label, so a.b.example.com is not covered by
                // *.example.com. Getting this wrong would install a certificate browsers reject.
                if (candidate.endsWith(suffix)
                        && candidate.length() > suffix.length()
                        && !candidate.substring(0, candidate.length() - suffix.length()).contains(".")) {
                    return true;
                }
            }
        }
        return false;
    }
}
