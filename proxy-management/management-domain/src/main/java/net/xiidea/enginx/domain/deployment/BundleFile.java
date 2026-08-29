package net.xiidea.enginx.domain.deployment;

import net.xiidea.enginx.domain.shared.ValidationException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * One file inside a configuration bundle.
 *
 * @param path      relative to the bundle root, always with forward slashes
 * @param sensitive private key material: written {@code 0600} on the host, never logged, and
 *                  never returned by any read endpoint
 */
public record BundleFile(String path, String content, String sha256, boolean sensitive) {

    public BundleFile {
        if (path == null || path.isBlank()) {
            throw new ValidationException("path", "Bundle file path must not be blank");
        }
        path = path.trim();
        // The agent verifies this too. Checking here as well means a traversal attempt cannot
        // even be constructed, let alone transmitted.
        if (path.startsWith("/") || path.contains("..") || path.contains("\\")) {
            throw new ValidationException("path", "Bundle file path must be relative and must not traverse");
        }
        if (content == null) {
            throw new ValidationException("content", "Bundle file content must not be null");
        }
    }

    public static BundleFile of(String path, String content) {
        return new BundleFile(path, content, digest(content), false);
    }

    public static BundleFile sensitive(String path, String content) {
        return new BundleFile(path, content, digest(content), true);
    }

    public static String digest(String content) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }
}
