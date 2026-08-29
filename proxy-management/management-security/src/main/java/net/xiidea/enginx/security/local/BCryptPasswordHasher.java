package net.xiidea.enginx.security.local;

import net.xiidea.enginx.application.identity.PasswordHasher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * bcrypt, at a cost that is deliberately slow.
 *
 * <p>Cost 12 is roughly a quarter of a second per hash on current hardware. That is a cost paid
 * once per login and is the entire point: it is also paid by anyone working through a stolen
 * password database, where it is the difference between billions of guesses a second and a few
 * hundred.
 *
 * <p>bcrypt rather than Argon2 only because it needs no native library and no tuning per host. If
 * that changes, this class is the only thing that changes — the hash format is self-describing and
 * the encoder can verify what it did not produce.
 */
@Component
public class BCryptPasswordHasher implements PasswordHasher {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);

    @Override
    public String hash(String plaintext) {
        return encoder.encode(plaintext);
    }

    @Override
    public boolean matches(String plaintext, String hash) {
        if (plaintext == null || hash == null) {
            return false;
        }
        // BCryptPasswordEncoder compares in constant time for a given hash, so a wrong password
        // costs the same as a right one.
        return encoder.matches(plaintext, hash);
    }
}
