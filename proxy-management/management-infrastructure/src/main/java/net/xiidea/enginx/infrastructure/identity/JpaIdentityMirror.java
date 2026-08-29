package net.xiidea.enginx.infrastructure.identity;

import net.xiidea.enginx.application.shared.IdentityMirror;
import net.xiidea.enginx.infrastructure.persistence.entity.AppGroupEntity;
import net.xiidea.enginx.infrastructure.persistence.entity.AppUserEntity;
import net.xiidea.enginx.infrastructure.persistence.repository.AppGroupJpaRepository;
import net.xiidea.enginx.infrastructure.persistence.repository.AppUserJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Populates the identity mirror from whoever is currently calling.
 *
 * <p>Writing on every request would add a database round trip to every API call for no benefit,
 * so a per-instance memo throttles it to roughly one write per subject per hour. The memo is
 * deliberately not shared between replicas: the cost of two instances each writing once an hour
 * is a rounding error, and coordinating it would be far more machinery than the value justifies.
 *
 * <p>Runs in its own transaction and swallows its own failures. A bookkeeping table must never be
 * able to fail the request that happened to trigger it.
 */
@Component
public class JpaIdentityMirror implements IdentityMirror {

    private static final Logger log = LoggerFactory.getLogger(JpaIdentityMirror.class);
    private static final Duration REFRESH_AFTER = Duration.ofHours(1);
    private static final int MAX_MEMO_ENTRIES = 10_000;

    private final AppUserJpaRepository users;
    private final AppGroupJpaRepository groups;
    private final Clock clock;
    private final Map<String, Instant> lastWritten = new ConcurrentHashMap<>();

    public JpaIdentityMirror(AppUserJpaRepository users, AppGroupJpaRepository groups, Clock clock) {
        this.users = users;
        this.groups = groups;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSeen(String subject, String username, String email, String displayName,
                           Set<String> groupPaths) {
        if (subject == null || subject.isBlank()) {
            return;
        }
        Instant now = clock.instant();
        if (!shouldWrite(subject, now)) {
            return;
        }

        try {
            AppUserEntity user = users.findByKeycloakSubject(subject)
                    .orElseGet(() -> {
                        AppUserEntity created = new AppUserEntity(UUID.randomUUID(), subject);
                        created.setCreatedAt(now);
                        return created;
                    });
            user.setUsername(username == null ? subject : username);
            user.setEmail(email);
            user.setDisplayName(displayName);
            user.setLastLoginAt(now);
            user.setUpdatedAt(now);
            users.save(user);

            for (String path : groupPaths) {
                if (groups.findByKeycloakGroupPath(path).isEmpty()) {
                    groups.save(new AppGroupEntity(UUID.randomUUID(), path, leafName(path), now));
                }
            }
            lastWritten.put(subject, now);
        } catch (RuntimeException e) {
            log.debug("Could not update the identity mirror for {}", subject, e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<MirroredUser> listUsers() {
        return users.findAllByOrderByUsernameAsc().stream()
                .map(u -> new MirroredUser(u.getKeycloakSubject(), u.getUsername(), u.getEmail(),
                        u.getDisplayName(), u.getLastLoginAt()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<MirroredGroup> listGroups() {
        return groups.findAllByOrderByKeycloakGroupPathAsc().stream()
                .map(g -> new MirroredGroup(g.getKeycloakGroupPath(), g.getName()))
                .toList();
    }

    private boolean shouldWrite(String subject, Instant now) {
        Instant previous = lastWritten.get(subject);
        if (previous != null && previous.plus(REFRESH_AFTER).isAfter(now)) {
            return false;
        }
        if (lastWritten.size() > MAX_MEMO_ENTRIES) {
            // Unbounded growth would be a slow leak on a large realm; the throttle is an
            // optimisation, so losing it costs an extra write rather than correctness.
            lastWritten.clear();
        }
        return true;
    }

    private static String leafName(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 && slash < path.length() - 1 ? path.substring(slash + 1) : path;
    }
}
