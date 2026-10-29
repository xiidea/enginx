package net.xiidea.enginx.domain.deployment;

import net.xiidea.enginx.domain.shared.ValidationException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * One file inside a configuration bundle.
 *
 * @param path   relative to the bundle root, always with forward slashes
 * @param access who on the host may read it, and whether a read endpoint may return it
 */
public record BundleFile(String path, String content, String sha256, Access access) {

    /** How a file is written on the host. */
    public enum Access {
        /** Configuration and public certificates: {@code 0644}. */
        PUBLIC("0644"),
        /** Private keys, read by the NGINX master as root: {@code 0600}. */
        PRIVATE("0600"),
        /**
         * Secrets the NGINX workers read on each request, such as an htpasswd file: {@code 0640},
         * group-owned by the worker group. Workers run unprivileged, so a {@link #PRIVATE} file
         * would pass {@code nginx -t} and then fail every request.
         */
        WORKER_READABLE("0640");

        private final String mode;

        Access(String mode) {
            this.mode = mode;
        }

        public String mode() {
            return mode;
        }
    }

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
        access = access == null ? Access.PUBLIC : access;
    }

    public BundleFile(String path, String content, String sha256, boolean sensitive) {
        this(path, content, sha256, sensitive ? Access.PRIVATE : Access.PUBLIC);
    }

    public static BundleFile of(String path, String content) {
        return new BundleFile(path, content, digest(content), Access.PUBLIC);
    }

    public static BundleFile sensitive(String path, String content) {
        return new BundleFile(path, content, digest(content), Access.PRIVATE);
    }

    public static BundleFile workerReadable(String path, String content) {
        return new BundleFile(path, content, digest(content), Access.WORKER_READABLE);
    }

    /** Secret material: never logged, and never returned by any read endpoint. */
    public boolean sensitive() {
        return access != Access.PUBLIC;
    }

    /** The octal mode the agent writes the file with. */
    public String mode() {
        return access.mode();
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
