package net.xiidea.enginx.security.local;

import net.xiidea.enginx.application.identity.PasswordHasher;
import net.xiidea.enginx.domain.identity.LocalUser;
import net.xiidea.enginx.domain.identity.LocalUserRepository;
import net.xiidea.enginx.domain.permission.GlobalRole;
import net.xiidea.enginx.security.AuthProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.Set;
import java.util.UUID;

/**
 * Creates the first administrator, once.
 *
 * <p>Without this, a deployment that has switched OIDC off starts with an empty user table and no
 * way in — and the only remedy is an INSERT with a bcrypt hash produced by hand.
 *
 * <p>It runs only when no local account exists at all. That condition, rather than "the bootstrap
 * user is missing", is what stops it recreating an account an administrator deliberately deleted,
 * and stops it resurrecting one that was disabled.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "enginx.auth.local-enabled", havingValue = "true")
public class LocalUserBootstrap {

    private static final Logger log = LoggerFactory.getLogger(LocalUserBootstrap.class);

    @Bean
    ApplicationRunner bootstrapLocalAdministrator(LocalUserRepository users, PasswordHasher passwords,
                                                  AuthProperties auth, Clock clock) {
        return args -> {
            if (users.count() > 0) {
                return;
            }
            if (auth.bootstrapUsername() == null || auth.bootstrapPassword() == null) {
                log.warn("Local authentication is enabled, no local account exists, and no bootstrap "
                        + "account is configured. Nobody can sign in locally. Set "
                        + "enginx.auth.bootstrap-username and enginx.auth.bootstrap-password.");
                return;
            }

            LocalUser admin = LocalUser.create(UUID.randomUUID(), auth.bootstrapUsername(),
                    passwords.hash(auth.bootstrapPassword()), null, "Bootstrap administrator",
                    Set.of(GlobalRole.SUPER_ADMIN), Set.of(),
                    // Flagged from the start: this password came from configuration, so it has been
                    // readable by everything that can read configuration — a deployment manifest, a
                    // shell history, whatever logged the environment on the last crash.
                    true, "system", clock.instant());

            users.save(admin);
            log.warn("Created bootstrap administrator '{}'. It must change its password at first "
                    + "login, and the configured value should be removed once it has.",
                    admin.username());
        };
    }
}
