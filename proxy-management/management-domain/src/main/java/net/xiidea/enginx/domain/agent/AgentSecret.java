package net.xiidea.enginx.domain.agent;

import net.xiidea.enginx.domain.shared.ValidationException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Minting and hashing the two credentials this feature issues.
 *
 * <p>Hashed with SHA-256 rather than bcrypt, which looks wrong next to
 * {@code BCryptPasswordHasher} and is not. Two reasons, and the second is the decisive one:
 *
 * <ol>
 *   <li>Slow hashing exists to make guessing a <em>low-entropy</em> secret expensive. These carry
 *       256 bits from a CSPRNG. There is nothing to guess, so there is nothing to slow down.
 *   <li>A bcrypt hash embeds a random salt, so the same input hashes differently every time and
 *       cannot be looked up. Verifying a presented token would mean reading every row and
 *       comparing against each — at which point the cost of bcrypt is paid per row, per request.
 * </ol>
 *
 * <p>The prefix is not decoration: it is what lets a secret scanner recognise one of these in a
 * commit or a log, and what tells whoever finds a stray string what they have found.
 */
public final class AgentSecret {

    /** Handed to a host so it can enrol itself. */
    public static final String REGISTRATION_PREFIX = "enginx-reg-";

    /** Issued to a host at enrolment, and presented on every call thereafter. */
    public static final String AGENT_PREFIX = "enginx-agt-";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int ENTROPY_BYTES = 32;

    private AgentSecret() {
    }

    public static String mintRegistrationToken() {
        return REGISTRATION_PREFIX + randomPart();
    }

    public static String mintAgentToken() {
        return AGENT_PREFIX + randomPart();
    }

    /**
     * @return the lowercase hex SHA-256 of the token, which is all that is ever stored
     */
    public static String hash(String token) {
        if (token == null || token.isBlank()) {
            throw new ValidationException("token", "A token is required");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.trim().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every JVM. If it is missing, nothing else here is safe either.
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String randomPart() {
        byte[] entropy = new byte[ENTROPY_BYTES];
        RANDOM.nextBytes(entropy);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
    }
}
