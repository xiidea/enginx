package net.xiidea.enginx.domain.identity;

import net.xiidea.enginx.domain.permission.GlobalRole;
import net.xiidea.enginx.domain.shared.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalUserTest {

    private static final Instant NOW = Instant.parse("2026-08-29T00:00:00Z");

    private static LocalUser user(String username) {
        return LocalUser.create(UUID.randomUUID(), username, "$2a$10$hash", null, null,
                Set.of(GlobalRole.OPERATOR), Set.of(), false, "root", NOW);
    }

    /**
     * The property that keeps local and federated identities from ever being confused.
     *
     * <p>{@code permission_grants.subject_ref} holds a Keycloak {@code sub} — a bare UUID — for a
     * federated grant. If a local account were identified by a bare UUID of its own, it could match
     * a grant written for somebody else, and the collision would be silent and permanent.
     */
    @Test
    @DisplayName("a local subject is namespaced, so it can never collide with a Keycloak subject")
    void subjectIsNamespaced() {
        LocalUser ada = user("ada");

        assertThat(ada.subjectRef()).startsWith("local:");
        assertThat(ada.subjectRef()).isEqualTo("local:" + ada.id());

        // A bare UUID is what Keycloak issues. The two sets must not intersect.
        assertThat(ada.subjectRef()).isNotEqualTo(ada.id().toString());
    }

    @Test
    @DisplayName("usernames are lowercased, so two accounts cannot differ only by case")
    void usernamesAreNormalised() {
        // Two accounts differing only in case is the shape of a convincing impersonation.
        assertThat(user("Ada").username()).isEqualTo("ada");
        assertThat(user("  ADA  ").username()).isEqualTo("ada");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ab", "-ada", "ada-", "ada space", "ada@example.com", "ада"})
    @DisplayName("a username that could be confused for something else is refused")
    void invalidUsernamesAreRefused(String candidate) {
        assertThatThrownBy(() -> user(candidate)).isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("a short password is refused before it is ever hashed")
    void shortPasswordsAreRefused() {
        assertThatThrownBy(() -> LocalUser.requireAcceptablePassword("short"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("12");

        LocalUser.requireAcceptablePassword("a-perfectly-ordinary-passphrase");
    }

    /**
     * The flag exists to force exactly this change, so clearing it is the aggregate's job. A caller
     * that changed the password without clearing it would lock the account into a permanent
     * change-password loop — and every caller would have to remember.
     */
    @Test
    @DisplayName("changing the password clears the must-change flag")
    void changingPasswordClearsTheFlag() {
        LocalUser bootstrap = LocalUser.create(UUID.randomUUID(), "root", "$2a$10$old", null, null,
                Set.of(GlobalRole.SUPER_ADMIN), Set.of(), true, "system", NOW);
        assertThat(bootstrap.mustChangePassword()).isTrue();

        bootstrap.changePassword("$2a$10$new", NOW.plusSeconds(60));

        assertThat(bootstrap.mustChangePassword()).isFalse();
        assertThat(bootstrap.passwordHash()).isEqualTo("$2a$10$new");
    }

    @Test
    @DisplayName("the aggregate never prints its own password hash")
    void toStringHidesTheHash() {
        // An aggregate that prints its credential ends up in a log line somebody keeps.
        assertThat(user("ada").toString()).doesNotContain("$2a$10$hash");
    }
}
