package net.xiidea.enginx.infrastructure.crypto;

import net.xiidea.enginx.application.proxy.HtpasswdHasher;
import org.apache.commons.codec.digest.Sha2Crypt;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * SHA-512-crypt at the default 5000 rounds, the {@code $6$} format NGINX hands to {@code crypt()}.
 *
 * <p>The salt is drawn here from a {@link SecureRandom} rather than left to the library, so its
 * quality does not depend on which overload a future version defaults to.
 */
@Component
public class Sha512CryptHtpasswdHasher implements HtpasswdHasher {

    private static final String SALT_ALPHABET = "./0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int SALT_LENGTH = 16;

    private final SecureRandom random = new SecureRandom();

    @Override
    public String hash(String password) {
        StringBuilder salt = new StringBuilder("$6$");
        for (int i = 0; i < SALT_LENGTH; i++) {
            salt.append(SALT_ALPHABET.charAt(random.nextInt(SALT_ALPHABET.length())));
        }
        return Sha2Crypt.sha512Crypt(password.getBytes(StandardCharsets.UTF_8), salt.toString());
    }
}
