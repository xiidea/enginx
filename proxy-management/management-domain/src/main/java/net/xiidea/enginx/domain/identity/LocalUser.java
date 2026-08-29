package net.xiidea.enginx.domain.identity;

import net.xiidea.enginx.domain.permission.GlobalRole;
import net.xiidea.enginx.domain.shared.ValidationException;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * An account the platform authenticates itself.
 *
 * <p>Exists so a deployment can run without an identity provider. Everything downstream — roles,
 * grants, the audit trail — treats a local account and a federated one identically, because both
 * arrive as the same validated token carrying the same claims. This aggregate is the only place
 * the difference is visible.
 *
 * <p>No password material lives here beyond an opaque hash the domain never inspects. Hashing is
 * an infrastructure concern and the algorithm will outlive this class.
 */
public final class LocalUser {

    /**
     * The prefix every local subject carries.
     *
     * <p>Load-bearing, not cosmetic. {@code permission_grants.subject_ref} holds a Keycloak
     * {@code sub} — a bare UUID — for a federated grant. A local account identified by a bare UUID
     * of its own could therefore match a grant written for somebody else entirely, and the
     * collision would be silent and permanent. Namespacing makes the two sets disjoint by
     * construction rather than by luck.
     */
    public static final String SUBJECT_PREFIX = "local:";

    private static final Pattern USERNAME = Pattern.compile("^[a-z0-9]([a-z0-9._-]{1,62}[a-z0-9])$");
    private static final int MIN_PASSWORD_LENGTH = 12;

    private final UUID id;
    private final String username;
    private String passwordHash;
    private String email;
    private String displayName;
    private boolean enabled;
    private boolean mustChangePassword;
    private Set<GlobalRole> roles;
    private Set<String> groupPaths;
    private Instant lastLoginAt;
    private final String createdBy;
    private final Instant createdAt;
    private Instant updatedAt;
    private final long version;

    private LocalUser(UUID id, String username, String passwordHash, String email, String displayName,
                      boolean enabled, boolean mustChangePassword, Set<GlobalRole> roles,
                      Set<String> groupPaths, Instant lastLoginAt, String createdBy,
                      Instant createdAt, Instant updatedAt, long version) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.email = email;
        this.displayName = displayName;
        this.enabled = enabled;
        this.mustChangePassword = mustChangePassword;
        this.roles = roles;
        this.groupPaths = groupPaths;
        this.lastLoginAt = lastLoginAt;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    public static LocalUser create(UUID id, String username, String passwordHash, String email,
                                   String displayName, Set<GlobalRole> roles, Set<String> groupPaths,
                                   boolean mustChangePassword, String createdBy, Instant now) {
        return new LocalUser(id, validUsername(username), requireHash(passwordHash),
                blankToNull(email), blankToNull(displayName), true, mustChangePassword,
                copyRoles(roles), copyGroups(groupPaths), null, createdBy, now, now, 0L);
    }

    public static LocalUser rehydrate(UUID id, String username, String passwordHash, String email,
                                      String displayName, boolean enabled, boolean mustChangePassword,
                                      Set<GlobalRole> roles, Set<String> groupPaths, Instant lastLoginAt,
                                      String createdBy, Instant createdAt, Instant updatedAt, long version) {
        return new LocalUser(id, username, passwordHash, email, displayName, enabled, mustChangePassword,
                copyRoles(roles), copyGroups(groupPaths), lastLoginAt, createdBy, createdAt,
                updatedAt, version);
    }

    /** The identity this account presents to every authorization decision. */
    public String subjectRef() {
        return SUBJECT_PREFIX + id;
    }

    public void changePassword(String newHash, Instant now) {
        this.passwordHash = requireHash(newHash);
        // Cleared here rather than by the caller: the flag exists to force exactly this, and a
        // path that changes the password without clearing it would lock the account into a
        // permanent change-password loop.
        this.mustChangePassword = false;
        this.updatedAt = now;
    }

    public void updateProfile(String newEmail, String newDisplayName, Set<GlobalRole> newRoles,
                              Set<String> newGroupPaths, Instant now) {
        this.email = blankToNull(newEmail);
        this.displayName = blankToNull(newDisplayName);
        this.roles = copyRoles(newRoles);
        this.groupPaths = copyGroups(newGroupPaths);
        this.updatedAt = now;
    }

    public void setEnabled(boolean newEnabled, Instant now) {
        this.enabled = newEnabled;
        this.updatedAt = now;
    }

    public void recordLogin(Instant now) {
        this.lastLoginAt = now;
    }

    /**
     * Rejects a password that is trivially guessable by length alone.
     *
     * <p>Length only, and deliberately. Composition rules — a digit, a symbol, mixed case — push
     * people toward one predictable pattern and are worth less than the characters they cost.
     * Anything beyond a floor belongs to a breach-corpus check, which is a network call this
     * platform should not make on a login path.
     */
    public static void requireAcceptablePassword(String plaintext) {
        if (plaintext == null || plaintext.length() < MIN_PASSWORD_LENGTH) {
            throw new ValidationException("password",
                    "A password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
    }

    private static String validUsername(String value) {
        if (value == null || value.isBlank()) {
            throw new ValidationException("username", "A username is required");
        }
        String normalised = value.trim().toLowerCase(Locale.ROOT);
        if (!USERNAME.matcher(normalised).matches()) {
            throw new ValidationException("username",
                    "A username must be 3 to 64 characters of lowercase letters, digits, dot, "
                            + "underscore or hyphen, and must start and end with a letter or digit");
        }
        return normalised;
    }

    private static String requireHash(String hash) {
        if (hash == null || hash.isBlank()) {
            throw new ValidationException("password", "A password hash is required");
        }
        return hash;
    }

    private static Set<GlobalRole> copyRoles(Set<GlobalRole> roles) {
        return roles == null ? Set.of() : Set.copyOf(roles);
    }

    private static Set<String> copyGroups(Set<String> groups) {
        if (groups == null) {
            return Set.of();
        }
        Set<String> copy = new LinkedHashSet<>();
        groups.stream().filter(g -> g != null && !g.isBlank()).map(String::trim).forEach(copy::add);
        return Set.copyOf(copy);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public UUID id() {
        return id;
    }

    public String username() {
        return username;
    }

    /** Opaque to everything but the password encoder. */
    public String passwordHash() {
        return passwordHash;
    }

    public String email() {
        return email;
    }

    public String displayName() {
        return displayName;
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean mustChangePassword() {
        return mustChangePassword;
    }

    public Set<GlobalRole> roles() {
        return roles;
    }

    public Set<String> groupPaths() {
        return groupPaths;
    }

    public Instant lastLoginAt() {
        return lastLoginAt;
    }

    public String createdBy() {
        return createdBy;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public long version() {
        return version;
    }

    /** Never includes the hash: an aggregate that prints its own credential ends up in a log. */
    @Override
    public String toString() {
        return "LocalUser[" + username + ", roles=" + roles + ", enabled=" + enabled + "]";
    }
}
