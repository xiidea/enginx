package net.xiidea.enginx.identity;

import net.xiidea.enginx.application.identity.AccessTokenIssuer;
import net.xiidea.enginx.application.identity.LocalAuthenticationService;
import net.xiidea.enginx.application.identity.LocalUserCommands;
import net.xiidea.enginx.application.identity.LocalUserService;
import net.xiidea.enginx.domain.identity.LocalUser;
import net.xiidea.enginx.domain.identity.LocalUserRepository;
import net.xiidea.enginx.domain.permission.GlobalRole;
import net.xiidea.enginx.domain.shared.ConflictException;
import net.xiidea.enginx.support.AbstractIntegrationTest;
import net.xiidea.enginx.support.TestSubjectProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Local authentication end to end, against PostgreSQL.
 *
 * <p>OIDC is switched off here, which is the configuration this feature exists to make possible:
 * the context has to start, and everything has to work, with no identity provider reachable.
 */
@Import(TestSubjectProvider.Config.class)
@TestPropertySource(properties = {
        "enginx.auth.oidc-enabled=false",
        "enginx.auth.local-enabled=true",
        "enginx.auth.jwt-secret=a-test-signing-secret-that-is-long-enough-for-hs256",
})
class LocalAuthenticationIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "a-perfectly-ordinary-passphrase";

    @Autowired
    private LocalUserService users;
    @Autowired
    private LocalAuthenticationService authentication;
    @Autowired
    private LocalUserRepository repository;
    @Autowired
    private TestSubjectProvider caller;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.execute("truncate local_users cascade");
        caller.actAsSuperAdmin();
    }

    private LocalUser create(String username, GlobalRole... roles) {
        return users.create(new LocalUserCommands.Create(username, PASSWORD, username + "@example.com",
                username, Set.of(roles), Set.of("/platform/operators"), false));
    }

    @Test
    @DisplayName("a local account signs in and receives a usable token")
    void signInIssuesAToken() {
        create("ada", GlobalRole.ADMIN);

        AccessTokenIssuer.IssuedToken issued = authentication.authenticate("ada", PASSWORD);

        assertThat(issued.token()).isNotBlank();
        assertThat(issued.expiresInSeconds()).isPositive();
    }

    @Test
    @DisplayName("a wrong password, a missing account and a disabled account are indistinguishable")
    void failuresAreIndistinguishable() {
        LocalUser ada = create("ada", GlobalRole.OPERATOR);
        users.setEnabled(ada.id(), false);

        // Same type, same message. Distinguishing them turns the login form into an account
        // enumerator, which is how a credential-stuffing list gets filtered to accounts worth
        // attacking.
        String wrongPassword = messageOf(() -> authentication.authenticate("ada", "wrong-password-here"));
        String noSuchUser = messageOf(() -> authentication.authenticate("nobody", PASSWORD));
        String disabled = messageOf(() -> authentication.authenticate("ada", PASSWORD));

        assertThat(wrongPassword).isEqualTo(noSuchUser).isEqualTo(disabled);
    }

    @Test
    @DisplayName("the stored password is a hash, never the password")
    void passwordIsStoredHashed() {
        create("ada", GlobalRole.OPERATOR);

        String stored = jdbc.queryForObject(
                "select password_hash from local_users where username = 'ada'", String.class);

        assertThat(stored).isNotEqualTo(PASSWORD);
        assertThat(stored).startsWith("$2");
        assertThat(stored).doesNotContain(PASSWORD);
    }

    @Test
    @DisplayName("signing in records the moment, so a dormant account is visible")
    void loginIsRecorded() {
        create("ada", GlobalRole.OPERATOR);
        assertThat(repository.findByUsername("ada").orElseThrow().lastLoginAt()).isNull();

        authentication.authenticate("ada", PASSWORD);

        assertThat(repository.findByUsername("ada").orElseThrow().lastLoginAt()).isNotNull();
    }

    @Test
    @DisplayName("changing a password invalidates the old one and accepts the new")
    void passwordCanBeChanged() {
        LocalUser ada = create("ada", GlobalRole.OPERATOR);

        users.changePassword(new LocalUserCommands.ChangePassword(ada.id(), null, "a-brand-new-passphrase"));

        assertThatThrownBy(() -> authentication.authenticate("ada", PASSWORD))
                .isInstanceOf(LocalAuthenticationService.AuthenticationFailedException.class);
        assertThat(authentication.authenticate("ada", "a-brand-new-passphrase").token()).isNotBlank();
    }

    /**
     * Deleting the only administrator locks everyone out of a running platform, and the only
     * remedy is a database edit. The check is cheap and is only ever wrong in the direction of
     * making someone create a second administrator first.
     */
    @Test
    @DisplayName("the last enabled administrator cannot be deleted or disabled")
    void lastAdministratorIsProtected() {
        LocalUser root = create("root", GlobalRole.SUPER_ADMIN);

        assertThatThrownBy(() -> users.delete(root.id()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("last enabled administrator");

        assertThatThrownBy(() -> users.setEnabled(root.id(), false))
                .isInstanceOf(ConflictException.class);

        // With a second administrator, the first may go.
        create("second", GlobalRole.SUPER_ADMIN);
        users.delete(root.id());
        assertThat(repository.findByUsername("root")).isEmpty();
    }

    @Test
    @DisplayName("two accounts cannot differ only by case")
    void usernamesAreUnique() {
        create("ada", GlobalRole.OPERATOR);

        assertThatThrownBy(() -> create("ADA", GlobalRole.OPERATOR))
                .isInstanceOf(ConflictException.class);
    }

    private static String messageOf(Runnable action) {
        try {
            action.run();
            throw new AssertionError("expected authentication to be rejected");
        } catch (LocalAuthenticationService.AuthenticationFailedException e) {
            return e.getMessage();
        }
    }
}
