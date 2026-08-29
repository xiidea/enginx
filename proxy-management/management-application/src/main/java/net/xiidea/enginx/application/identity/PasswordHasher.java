package net.xiidea.enginx.application.identity;

/**
 * Hashes and verifies passwords.
 *
 * <p>A port because the algorithm is an infrastructure decision that will change — bcrypt's cost
 * factor is already a moving target — and because it keeps every use case here free of a
 * dependency on whichever library is current.
 */
public interface PasswordHasher {

    String hash(String plaintext);

    /**
     * Verifies a candidate against a stored hash.
     *
     * <p>Implementations must take the same time whether or not the hash is valid. A comparison
     * that returns early on the first wrong byte leaks the hash one character at a time.
     */
    boolean matches(String plaintext, String hash);
}
