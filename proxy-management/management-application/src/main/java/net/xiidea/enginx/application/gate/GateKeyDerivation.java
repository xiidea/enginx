package net.xiidea.enginx.application.gate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * The derivations the gate and the platform must agree on, byte for byte.
 *
 * <p>The site key is never used directly: a session cookie and a relay request are signed with
 * different subkeys, so a value that verifies as one can never verify as the other. The gate's Go
 * implementation ({@code enginx-agent/internal/gate}) mirrors this class; the labels are part of
 * the protocol and must not change without a version bump on both sides.
 */
public final class GateKeyDerivation {

    static final String RELAY_LABEL = "enginx-gate relay v1";
    static final String SESSION_LABEL = "enginx-gate session v1";

    private GateKeyDerivation() {
    }

    public static byte[] relayKey(byte[] siteKey) {
        return hmac(siteKey, RELAY_LABEL.getBytes(StandardCharsets.UTF_8));
    }

    public static byte[] sessionKey(byte[] siteKey) {
        return hmac(siteKey, SESSION_LABEL.getBytes(StandardCharsets.UTF_8));
    }

    /** The relay signature: hex HMAC-SHA256 over {@code siteId \n email \n code \n timestamp}. */
    public static String relaySignature(byte[] siteKey, String siteId, String email, String code, long timestamp) {
        String message = siteId + "\n" + email + "\n" + code + "\n" + timestamp;
        return HexFormat.of().formatHex(hmac(relayKey(siteKey), message.getBytes(StandardCharsets.UTF_8)));
    }

    /** Constant-time, so a signature cannot be found a byte at a time by timing the refusal. */
    public static boolean signatureMatches(String expected, String presented) {
        if (presented == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                presented.getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] hmac(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is required by the Java platform", e);
        }
    }
}
