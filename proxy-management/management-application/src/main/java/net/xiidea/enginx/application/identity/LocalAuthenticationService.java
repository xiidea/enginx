package net.xiidea.enginx.application.identity;

import net.xiidea.enginx.application.shared.AuditRecorder;
import net.xiidea.enginx.domain.audit.AuditAction;
import net.xiidea.enginx.domain.identity.LocalUser;
import net.xiidea.enginx.domain.identity.LocalUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;

/**
 * Verifies a username and password, and issues a token.
 *
 * <p>Only reachable when local authentication is enabled; the endpoint is not registered otherwise,
 * so a deployment that federates everything has no password surface at all.
 */
@Service
public class LocalAuthenticationService {

    private static final Logger log = LoggerFactory.getLogger(LocalAuthenticationService.class);
    private static final String RESOURCE_TYPE = "LOCAL_USER";

    /**
     * One message for every failure.
     *
     * <p>"No such user" and "wrong password" are the same sentence on purpose. Distinguishing them
     * turns the login form into an account enumerator, which is how a credential-stuffing list
     * gets filtered down to the accounts worth attacking.
     */
    private static final String REJECTED = "Invalid username or password";

    private final LocalUserRepository users;
    private final PasswordHasher passwords;
    private final AccessTokenIssuer tokens;
    private final AuditRecorder audit;
    private final Clock clock;

    public LocalAuthenticationService(LocalUserRepository users, PasswordHasher passwords,
                                      AccessTokenIssuer tokens, AuditRecorder audit, Clock clock) {
        this.users = users;
        this.passwords = passwords;
        this.tokens = tokens;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * @throws AuthenticationFailedException when the credentials are wrong or the account is off
     */
    @Transactional
    public AccessTokenIssuer.IssuedToken authenticate(String username, String password) {
        Optional<LocalUser> found = users.findByUsername(username);

        if (found.isEmpty()) {
            // Hashed anyway, against a throwaway value. Returning early here makes a missing
            // account measurably faster than a wrong password, and that difference is enough to
            // enumerate accounts over the network without ever guessing one right.
            passwords.matches(password == null ? "" : password, DUMMY_HASH);
            reject(username, "no such account");
        }

        LocalUser user = found.get();
        if (!user.enabled()) {
            reject(username, "account disabled");
        }
        if (password == null || !passwords.matches(password, user.passwordHash())) {
            reject(username, "wrong password");
        }

        user.recordLogin(clock.instant());
        users.save(user);

        audit.success(AuditAction.LOCAL_USER_LOGGED_IN, RESOURCE_TYPE, user.id(), null,
                Map.of("username", user.username(),
                        "mustChangePassword", String.valueOf(user.mustChangePassword())));

        return tokens.issue(user);
    }

    /**
     * A valid bcrypt hash of a value nothing will ever submit, so the comparison above costs the
     * same work as a real one.
     */
    private static final String DUMMY_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private void reject(String username, String reason) {
        // The reason reaches the audit trail and the log, never the caller. An operator
        // investigating a lockout needs to know which of the three it was; an attacker must not.
        log.info("Local login rejected for '{}': {}", username, reason);
        audit.denied(AuditAction.LOCAL_USER_LOGIN_FAILED, RESOURCE_TYPE, null,
                "Login rejected for '" + username + "': " + reason);
        throw new AuthenticationFailedException(REJECTED);
    }

    /** Credentials were not accepted. Carries nothing that distinguishes why. */
    public static class AuthenticationFailedException extends RuntimeException {
        public AuthenticationFailedException(String message) {
            super(message);
        }
    }
}
