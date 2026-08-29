package net.xiidea.enginx.identity;

import net.xiidea.enginx.application.identity.IdentityDirectoryService;
import net.xiidea.enginx.application.identity.LocalUserCommands;
import net.xiidea.enginx.application.identity.LocalUserService;
import net.xiidea.enginx.application.shared.IdentityMirror;
import net.xiidea.enginx.domain.identity.LocalUser;
import net.xiidea.enginx.domain.permission.GlobalRole;
import net.xiidea.enginx.domain.shared.PageResult;
import net.xiidea.enginx.domain.shared.ValidationException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    private IdentityDirectoryService directory;
    @Autowired
    private net.xiidea.enginx.application.permission.PermissionGrantService grantService;
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
        jdbc.execute("truncate permission_grants cascade");
        caller.actAsSuperAdmin();
    }

    private List<String> staleUsernames(Integer dormantForDays) {
        return directory.find(new IdentityDirectoryService.Query(null, true, dormantForDays, 0, 50))
                .content().stream()
                .map(entry -> entry.user().username())
                .toList();
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
        return mirror.findUsers(new IdentityMirror.DirectoryQuery(search, false, null, 0, 10)).content().stream()
                .map(IdentityMirror.MirroredUser::username).toList();
    }

    @Test
    @DisplayName("the listing is paged, and reports a total beyond the page")
    void listingIsPaged() {
        for (int i = 0; i < 25; i++) {
            seen(String.format("user%02d", i), "User " + i);
        }

        PageResult<IdentityMirror.MirroredUser> first = mirror.findUsers(new IdentityMirror.DirectoryQuery(null, false, null, 0, 10));

        assertThat(first.content()).hasSize(10);
        assertThat(first.totalElements()).isEqualTo(25);
        assertThat(first.totalPages()).isEqualTo(3);
        // Ordered, so paging is stable rather than showing the same person on two pages.
        assertThat(mirror.findUsers(new IdentityMirror.DirectoryQuery(null, false, null, 1, 10)).content())
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
        assertThat(refsOf(mirror.findUsers(new IdentityMirror.DirectoryQuery(null, false, null, 0, 10)))).contains(ada.subjectRef());

        users.delete(ada.id());

        // Left behind, this keeps appearing as somebody you can grant to, and the grant would
        // silently do nothing because no token will ever carry the subject again.
        assertThat(refsOf(mirror.findUsers(new IdentityMirror.DirectoryQuery(null, false, null, 0, 10)))).doesNotContain(ada.subjectRef());
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

        List<IdentityMirror.MirroredUser> found = mirror.findUsers(new IdentityMirror.DirectoryQuery(null, false, null, 0, 10)).content();

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

    /**
     * Removing entries that no longer earn their place.
     *
     * <p>Nothing here is an authorization change: an entry is a name the console can offer when
     * authoring a grant. Removing one takes away the name, never the access — which is exactly
     * why it has to be careful about entries an access rule still refers to.
     */
    @org.junit.jupiter.api.Nested
    @DisplayName("cleaning up stale entries")
    class Cleanup {

        @Test
        @DisplayName("a local subject with no account is stale; a federated one never is")
        void onlyLocalSubjectsAreProvablyGone() {
            LocalUser ada = users.create(new LocalUserCommands.Create("ada", PASSWORD, null, "Ada",
                    Set.of(GlobalRole.OPERATOR), Set.of(), false));
            seenAs(ada.subjectRef(), "ada", "Ada");
            seenAs(LocalUser.SUBJECT_PREFIX + UUID.randomUUID(), "ghost", "Ghost");
            seen("external", "External Person");

            // The federated subject may well be gone too, but this platform cannot ask, and
            // guessing would sweep up everyone who signs in through a provider.
            assertThat(staleUsernames(null)).containsExactly("ghost");
        }

        @Test
        @DisplayName("dormancy is opt-in, and has a floor")
        void dormancyIsOptInAndFloored() {
            seen("recent", "Recently Seen");
            assertThat(staleUsernames(null)).isEmpty();

            // Everything here was seen just now, so a 30-day cutoff still matches nothing.
            assertThat(staleUsernames(30)).isEmpty();

            // A cutoff short enough to describe a weekend would quietly empty the directory.
            assertThatThrownBy(() -> staleUsernames(2))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("30 days");
        }

        @Test
        @DisplayName("cleanup removes the stale and leaves everything else")
        void cleanupRemovesOnlyTheStale() {
            LocalUser ada = users.create(new LocalUserCommands.Create("ada", PASSWORD, null, "Ada",
                    Set.of(GlobalRole.OPERATOR), Set.of(), false));
            seenAs(ada.subjectRef(), "ada", "Ada");
            String ghost = seenAs(LocalUser.SUBJECT_PREFIX + UUID.randomUUID(), "ghost", "Ghost");
            seen("external", "External Person");

            IdentityDirectoryService.Result result =
                    directory.cleanup(new IdentityDirectoryService.Command(null, false));

            assertThat(result.removed()).isEqualTo(1);
            assertThat(result.removedSubjects()).containsExactly(ghost);
            assertThat(refsOf(mirror.findUsers(new IdentityMirror.DirectoryQuery(null, false, null, 0, 50))))
                    .containsExactlyInAnyOrder(ada.subjectRef(), subjectOf("external"));
        }

        /**
         * The rule worth having. A grant names a subject reference; remove the row and the grant
         * still works but shows as a bare identifier nobody can attribute — an access rule made
         * unreadable, which is worse than an untidy picker.
         */
        @Test
        @DisplayName("an entry a grant still names is skipped, and says so")
        void grantedSubjectsAreSkipped() {
            String ghost = seenAs(LocalUser.SUBJECT_PREFIX + UUID.randomUUID(), "ghost", "Ghost");
            grantTo(ghost);

            IdentityDirectoryService.Result skipped =
                    directory.cleanup(new IdentityDirectoryService.Command(null, false));

            assertThat(skipped.removed()).isZero();
            assertThat(skipped.skippedBecauseGranted()).isEqualTo(1);
            assertThat(refsOf(mirror.findUsers(new IdentityMirror.DirectoryQuery(null, false, null, 0, 50))))
                    .contains(ghost);

            // ...unless the caller says otherwise, having been told.
            IdentityDirectoryService.Result forced =
                    directory.cleanup(new IdentityDirectoryService.Command(null, true));
            assertThat(forced.removed()).isEqualTo(1);
        }

        /** Removing a name must never remove access; those are different decisions. */
        @Test
        @DisplayName("cleanup never revokes a grant")
        void cleanupLeavesGrantsAlone() {
            String ghost = seenAs(LocalUser.SUBJECT_PREFIX + UUID.randomUUID(), "ghost", "Ghost");
            grantTo(ghost);

            directory.cleanup(new IdentityDirectoryService.Command(null, true));

            assertThat(jdbc.queryForObject("select count(*) from permission_grants", Integer.class))
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("the listing says which entries a grant names, so the skip is not a surprise")
        void listingReportsGrantReferences() {
            String ghost = seenAs(LocalUser.SUBJECT_PREFIX + UUID.randomUUID(), "ghost", "Ghost");
            grantTo(ghost);
            seenAs(LocalUser.SUBJECT_PREFIX + UUID.randomUUID(), "unreferenced", "Nobody");

            List<IdentityDirectoryService.Entry> entries =
                    directory.find(new IdentityDirectoryService.Query(null, true, null, 0, 50)).content();

            assertThat(entries).filteredOn(e -> e.user().username().equals("ghost"))
                    .singleElement().extracting(IdentityDirectoryService.Entry::hasGrants).isEqualTo(true);
            assertThat(entries).filteredOn(e -> e.user().username().equals("unreferenced"))
                    .singleElement().extracting(IdentityDirectoryService.Entry::hasGrants).isEqualTo(false);
        }

        /**
         * A body that names nothing means "use the defaults", and must not be a 400.
         *
         * <p>Guarded because it is invisible in Java and easy to reintroduce: a record component
         * declared as a primitive makes Jackson reject any body that omits it, so a field the
         * schema marks optional becomes required in practice.
         */
        @Test
        @DisplayName("a cleanup that names no options uses the safe ones")
        void defaultsAreUsedWhenNothingIsAsked() {
            String ghost = seenAs(LocalUser.SUBJECT_PREFIX + UUID.randomUUID(), "ghost", "Ghost");

            IdentityDirectoryService.Result result =
                    directory.cleanup(new IdentityDirectoryService.Command(null, false));

            assertThat(result.removedSubjects()).containsExactly(ghost);
        }

        private void grantTo(String subjectRef) {
            grantService.grant(new net.xiidea.enginx.application.permission.PermissionCommands.Grant(
                    net.xiidea.enginx.domain.permission.SubjectType.USER, subjectRef,
                    net.xiidea.enginx.domain.permission.ScopeType.GLOBAL, null, null, null,
                    net.xiidea.enginx.domain.permission.PermissionLevel.READ, null));
        }

        private String subjectOf(String username) {
            return mirror.findByUsername(username).getFirst().subject();
        }
    }
}
