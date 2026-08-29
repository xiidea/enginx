package net.xiidea.enginx.infrastructure.identity;

import net.xiidea.enginx.application.shared.IdentityMirror;
import net.xiidea.enginx.domain.identity.LocalUser;
import net.xiidea.enginx.domain.shared.PageResult;
import net.xiidea.enginx.infrastructure.persistence.entity.AppGroupEntity;
import net.xiidea.enginx.infrastructure.persistence.entity.AppUserEntity;
import net.xiidea.enginx.infrastructure.persistence.repository.AppGroupJpaRepository;
import net.xiidea.enginx.infrastructure.persistence.repository.AppUserJpaRepository;
import net.xiidea.enginx.infrastructure.persistence.repository.LocalUserJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

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
    private final LocalUserJpaRepository localUsers;
    private final Clock clock;
    private final Map<String, Instant> lastWritten = new ConcurrentHashMap<>();

    public JpaIdentityMirror(AppUserJpaRepository users, AppGroupJpaRepository groups,
                             LocalUserJpaRepository localUsers, Clock clock) {
        this.users = users;
        this.groups = groups;
        this.localUsers = localUsers;
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
    public PageResult<MirroredUser> findUsers(DirectoryQuery query) {
        String pattern = likePattern(query.search());
        // The native stale query carries its own ordering; the derived one takes a Sort.
        Page<AppUserEntity> found = query.staleOnly()
                ? users.searchStale(pattern, query.dormantBefore(),
                        PageRequest.of(query.page(), query.size()))
                : users.search(pattern,
                        PageRequest.of(query.page(), query.size(), Sort.by("username").ascending()));

        return new PageResult<>(toMirrored(found.getContent()),
                query.page(), query.size(), found.getTotalElements());
    }

    @Override
    @Transactional(readOnly = true)
    public List<MirroredUser> findStale(Instant dormantBefore) {
        // Unpaged deliberately: a cleanup decides on the whole set at once, and paging through a
        // list while deleting from it silently skips half of it.
        return toMirrored(users.searchStale("%", dormantBefore, Pageable.unpaged()).getContent());
    }

    @Override
    @Transactional
    public int forgetAll(Collection<String> subjects) {
        if (subjects.isEmpty()) {
            return 0;
        }
        users.deleteByKeycloakSubjectIn(subjects);
        subjects.forEach(lastWritten::remove);
        return subjects.size();
    }

    private List<MirroredUser> toMirrored(List<AppUserEntity> entities) {
        Set<String> stillPresent = localSubjectsPresentIn(entities);
        return entities.stream()
                .map(u -> new MirroredUser(u.getKeycloakSubject(), u.getUsername(), u.getEmail(),
                        u.getDisplayName(), u.getLastLoginAt(),
                        isPresent(u.getKeycloakSubject(), stillPresent)))
                .toList();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void forget(String subject) {
        if (subject == null || subject.isBlank()) {
            return;
        }
        try {
            users.deleteByKeycloakSubject(subject);
            // Otherwise the throttle keeps believing this subject was written recently, and a
            // later sign-in under the same subject would not be recorded for up to an hour.
            lastWritten.remove(subject);
        } catch (RuntimeException e) {
            log.debug("Could not remove {} from the identity mirror", subject, e);
        }
    }

    /**
     * Which of the page's local subjects still resolve to an account.
     *
     * <p>One query for the page rather than one per row. Only local subjects are looked up: an
     * external provider's users are not this platform's to track, and asking it on every listing
     * would put a network call behind a dropdown.
     */
    private Set<String> localSubjectsPresentIn(List<AppUserEntity> page) {
        Set<String> localSubjects = page.stream()
                .map(AppUserEntity::getKeycloakSubject)
                .filter(subject -> subject != null && subject.startsWith(LocalUser.SUBJECT_PREFIX))
                .collect(Collectors.toSet());

        if (localSubjects.isEmpty()) {
            return Set.of();
        }

        Set<UUID> ids = localSubjects.stream()
                .map(subject -> parseId(subject.substring(LocalUser.SUBJECT_PREFIX.length())))
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());

        return localUsers.findAllById(ids).stream()
                .map(entity -> LocalUser.SUBJECT_PREFIX + entity.getId())
                .collect(Collectors.toSet());
    }

    private static boolean isPresent(String subject, Set<String> stillPresentLocalSubjects) {
        if (subject == null || !subject.startsWith(LocalUser.SUBJECT_PREFIX)) {
            // Not ours to judge. Reporting an external subject as absent would be a guess, and
            // the guess would always be "absent" for a provider this platform cannot query.
            return true;
        }
        return stillPresentLocalSubjects.contains(subject);
    }

    private static UUID parseId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            // A subject that is namespaced but not a UUID was not written by this platform.
            return null;
        }
    }

    /**
     * Turns typed text into a LIKE pattern, escaping the two characters that would otherwise be
     * wildcards. A blank search becomes {@code %}, which matches everyone.
     *
     * <p>{@code !} is the escape character, matching the {@code escape} clause on the query. The
     * escape character itself has to be escaped first, or a search for {@code !} would produce a
     * dangling escape.
     */
    private static String likePattern(String search) {
        if (search == null || search.isBlank()) {
            return "%";
        }
        String escaped = search.trim().toLowerCase(Locale.ROOT)
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
        return "%" + escaped + "%";
    }

    @Override
    @Transactional(readOnly = true)
    public List<MirroredUser> findByUsername(String username) {
        if (username == null || username.isBlank()) {
            return List.of();
        }
        return users.findByUsernameIgnoreCaseOrderByLastLoginAtDesc(username.trim()).stream()
                .map(u -> new MirroredUser(u.getKeycloakSubject(), u.getUsername(), u.getEmail(),
                        u.getDisplayName(), u.getLastLoginAt(), true))
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
