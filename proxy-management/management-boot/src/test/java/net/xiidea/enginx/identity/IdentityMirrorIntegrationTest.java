package net.xiidea.enginx.identity;

import net.xiidea.enginx.application.identity.LocalUserCommands;
import net.xiidea.enginx.application.identity.LocalUserService;
import net.xiidea.enginx.application.shared.IdentityMirror;
import net.xiidea.enginx.domain.identity.LocalUser;
import net.xiidea.enginx.domain.permission.GlobalRole;
import net.xiidea.enginx.domain.shared.PageResult;
import net.xiidea.enginx.support.AbstractIntegrationTest;
import net.xiidea.enginx.support.TestSubjectProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The directory of everyone who has signed in.
 *
 * <p>It is a convenience index for grant authoring, never an authorization input — but it is the
 * list a person picks from when granting permanent access, so being wrong about who is in it has
 * consequences of its own.
 */
@Import(TestSubjectProvider.Config.class)
@TestPropertySource(properties = {
        "enginx.auth.oidc-enabled=false",
        "enginx.auth.local-enabled=true",
        "enginx.auth.jwt-secret=a-test-signing-secret-that-is-long-enough-for-hs256",
})
class IdentityMirrorIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "a-perfectly-ordinary-passphrase";

    @Autowired
    private IdentityMirror mirror;
    @Autowired
    private LocalUserService users;
    @Autowired
    private TestSubjectProvider caller;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.execute("truncate local_users cascade");
        jdbc.execute("truncate app_users cascade");
        caller.actAsSuperAdmin();
    }

    /**
     * Records a sighting under a subject nothing else has used.
     *
     * <p>Unique per call on purpose: the mirror throttles repeat writes for a subject in an
     * in-memory memo, and that memo outlives this class's {@code truncate}. Reusing a subject
     * across tests would silently record nothing.
     *
     * @return the generated subject
     */
    private String seen(String username, String displayName) {
        return seenAs(UUID.randomUUID().toString(), username, displayName);
    }

    /** For the cases that care what the subject looks like, rather than only that it is unique. */
    private String seenAs(String subject, String username, String displayName) {
        mirror.recordSeen(subject, username, username + "@example.com", displayName, Set.of());
        return subject;
    }

    private List<String> refsOf(PageResult<IdentityMirror.MirroredUser> page) {
        return page.content().stream().map(IdentityMirror.MirroredUser::subject).toList();
    }

    private List<String> usernamesMatching(String search) {
        return mirror.findUsers(search, 0, 10).content().stream()
                .map(IdentityMirror.MirroredUser::username).toList();
    }

    @Test
    @DisplayName("the listing is paged, and reports a total beyond the page")
    void listingIsPaged() {
        for (int i = 0; i < 25; i++) {
            seen(String.format("user%02d", i), "User " + i);
        }

        PageResult<IdentityMirror.MirroredUser> first = mirror.findUsers(null, 0, 10);

        assertThat(first.content()).hasSize(10);
        assertThat(first.totalElements()).isEqualTo(25);
        assertThat(first.totalPages()).isEqualTo(3);
        // Ordered, so paging is stable rather than showing the same person on two pages.
        assertThat(mirror.findUsers(null, 1, 10).content())
                .doesNotContainAnyElementsOf(first.content());
    }

    @Test
    @DisplayName("search matches the username and the display name, case-insensitively")
    void searchMatchesBothNames() {
        seen("ada", "Ada Lovelace");
        seen("grace", "Grace Hopper");
        seen("kat", "Katherine Johnson");

        assertThat(usernamesMatching("ADA")).containsExactly("ada");
        assertThat(usernamesMatching("hopper")).containsExactly("grace");
        assertThat(usernamesMatching("a")).containsExactlyInAnyOrder("ada", "grace", "kat");
        assertThat(usernamesMatching("  ")).hasSize(3);
    }

    /**
     * The search box is typed into by a person, and {@code %} is an ordinary character to them.
     * Unescaped it is a wildcard, so a search for it would silently return everyone.
     */
    @Test
    @DisplayName("wildcard characters in the search text are matched literally")
    void wildcardsInSearchAreEscaped() {
        seen("ada", "Ada Lovelace");
        seen("od_d", "Odd Name");
        seen("fifty", "50% Effort");

        // Unescaped these are wildcards and would return all three. Escaped, they find only the
        // rows that genuinely contain the character -- which is the stronger claim, because it
        // also shows the search is still working rather than merely refusing everything.
        assertThat(usernamesMatching("%")).containsExactly("fifty");
        assertThat(usernamesMatching("_")).containsExactly("od_d");

        assertThat(usernamesMatching("od_d")).containsExactly("od_d");
        // The underscore is literal, so it must not match 'odXd'.
        assertThat(usernamesMatching("odXd")).isEmpty();
        assertThat(usernamesMatching("50%")).containsExactly("fifty");
        // The escape character itself is ordinary text to whoever typed it.
        assertThat(usernamesMatching("!")).isEmpty();
    }

    @Test
    @DisplayName("deleting a local account removes it from the directory")
    void deletingALocalAccountPrunesTheMirror() {
        LocalUser ada = users.create(new LocalUserCommands.Create("ada", PASSWORD, "ada@example.com",
                "Ada", Set.of(GlobalRole.OPERATOR), Set.of(), false));
        seenAs(ada.subjectRef(), "ada", "Ada");
        assertThat(refsOf(mirror.findUsers(null, 0, 10))).contains(ada.subjectRef());

        users.delete(ada.id());

        // Left behind, this keeps appearing as somebody you can grant to, and the grant would
        // silently do nothing because no token will ever carry the subject again.
        assertThat(refsOf(mirror.findUsers(null, 0, 10))).doesNotContain(ada.subjectRef());
    }

    /**
     * The other half: a subject the platform knows is gone, but which it did not delete itself —
     * here, a row left over from an account removed before pruning existed.
     */
    @Test
    @DisplayName("a local subject with no account is reported as no longer present")
    void orphanedLocalSubjectIsMarkedAbsent() {
        LocalUser ada = users.create(new LocalUserCommands.Create("ada", PASSWORD, null, "Ada",
                Set.of(GlobalRole.OPERATOR), Set.of(), false));
        seenAs(ada.subjectRef(), "ada", "Ada");
        seenAs(LocalUser.SUBJECT_PREFIX + UUID.randomUUID(), "ghost", "Ghost");
        seen("external", "External Person");

        List<IdentityMirror.MirroredUser> found = mirror.findUsers(null, 0, 10).content();

        assertThat(found).filteredOn(u -> u.username().equals("ada")).singleElement()
                .extracting(IdentityMirror.MirroredUser::present).isEqualTo(true);
        assertThat(found).filteredOn(u -> u.username().equals("ghost")).singleElement()
                .extracting(IdentityMirror.MirroredUser::present).isEqualTo(false);
        // Not this platform's to judge: it cannot ask an external provider, and guessing would
        // mean reporting every federated user as gone.
        assertThat(found).filteredOn(u -> u.username().equals("external")).singleElement()
                .extracting(IdentityMirror.MirroredUser::present).isEqualTo(true);
    }

    @Test
    @DisplayName("a username can belong to more than one subject, most recently seen first")
    void oneUsernameCanHaveSeveralSubjects() {
        // Two providers can each have an 'admin'; the mirror is keyed by subject, not by name.
        // Fresh subjects, because the mirror throttles repeat writes per subject in a memo that
        // outlives this test's truncate.
        seenAs(LocalUser.SUBJECT_PREFIX + UUID.randomUUID(), "admin", "Local Admin");
        seen("admin", "Federated Admin");

        assertThat(mirror.findByUsername("admin")).hasSize(2);
        assertThat(mirror.findByUsername("ADMIN")).hasSize(2);
        assertThat(mirror.findByUsername("nobody")).isEmpty();
    }
}
