package net.xiidea.enginx.infrastructure.agent;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * Builds the TLS context for talking to an agent.
 *
 * <p>Two checks, not one. The CA must have signed the agent's certificate, and the certificate's
 * fingerprint must be the one recorded when that instance was registered. The CA alone is not
 * enough: it has signed every agent in the estate, so a certificate it issued for one host would
 * otherwise be accepted as any other host, and a compromised agent could impersonate the rest.
 */
final class AgentTlsFactory {

    private AgentTlsFactory() {
    }

    /**
     * Builds a context pinned to exactly one agent certificate.
     *
     * <p>The pin is baked in rather than set per request. A single context with a mutable
     * expected fingerprint would race as soon as two instances were deployed to at once, and the
     * losing thread would validate the wrong host against the wrong pin — a failure that would
     * appear only under concurrency and would silently accept the wrong agent. One context per
     * fingerprint costs a handful of objects and removes the question.
     */
    static SSLContext create(AgentClientProperties properties, String expectedFingerprint)
            throws GeneralSecurityException, IOException {

        KeyStore identity = load(properties.keyStore(), properties.keyStorePassword());
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(identity, chars(properties.keyStorePassword()));

        KeyStore trust = load(properties.trustStore(), properties.trustStorePassword());
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(trust);

        X509TrustManager caTrustManager = Arrays.stream(trustManagers.getTrustManagers())
                .filter(X509TrustManager.class::isInstance)
                .map(X509TrustManager.class::cast)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No X509 trust manager in " + properties.trustStore()));

        SSLContext context = SSLContext.getInstance("TLSv1.3");
        context.init(keyManagers.getKeyManagers(),
                new TrustManager[]{new PinnedTrustManager(caTrustManager, expectedFingerprint)},
                null);
        return context;
    }

    private static KeyStore load(String path, String password) throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(Path.of(path))) {
            store.load(in, chars(password));
        }
        return store;
    }

    private static char[] chars(String password) {
        return password == null ? new char[0] : password.toCharArray();
    }

    /** Verifies the chain against the CA, then checks the leaf against the registered fingerprint. */
    private record PinnedTrustManager(X509TrustManager delegate, String expectedFingerprint)
            implements X509TrustManager {

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            delegate.checkClientTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            delegate.checkServerTrusted(chain, authType);

            String expected = expectedFingerprint;
            if (expected == null || expected.isBlank()) {
                throw new CertificateException(
                        "No expected agent fingerprint was set for this call; refusing to trust the peer");
            }
            String actual = fingerprintOf(chain[0]);
            if (!expected.equalsIgnoreCase(actual)) {
                throw new CertificateException("The agent presented certificate " + actual
                        + " but this instance is registered with " + expected);
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return delegate.getAcceptedIssuers();
        }

        private static String fingerprintOf(X509Certificate certificate) throws CertificateException {
            try {
                MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
                return HexFormat.of().formatHex(sha256.digest(certificate.getEncoded())).toUpperCase();
            } catch (Exception e) {
                throw new CertificateException("Could not fingerprint the agent certificate", e);
            }
        }
    }

}
