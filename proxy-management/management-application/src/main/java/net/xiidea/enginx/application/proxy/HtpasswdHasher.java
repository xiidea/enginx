package net.xiidea.enginx.application.proxy;

/**
 * Hashes a basic authentication password into the form NGINX checks it against.
 *
 * <p>Separate from {@link net.xiidea.enginx.application.identity.PasswordHasher}, deliberately:
 * that one protects console accounts and is free to be slow, while this one is verified by NGINX
 * on every request to a protected site, with no cache, through the system {@code crypt()}. The
 * output must be SHA-512-crypt ({@code $6$}), the strongest scheme both musl and glibc implement.
 */
public interface HtpasswdHasher {

    /** A SHA-512-crypt hash with a fresh random salt. */
    String hash(String password);
}
