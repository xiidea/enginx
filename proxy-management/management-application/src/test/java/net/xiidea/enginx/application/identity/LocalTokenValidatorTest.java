package net.xiidea.enginx.application.identity;

import net.xiidea.enginx.domain.identity.LocalUser;
import net.xiidea.enginx.domain.identity.LocalUserRepository;
import net.xiidea.enginx.domain.permission.GlobalRole;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rule that closes the revocation window: a local token is only good while the account behind
 * it is still enabled and has not changed since the token was minted.
 */
class LocalTokenValidatorTest {

    private static final Instant CHANGED = Instant.parse("2026-08-30T10:00:00Z");

    private final UUID id = UUID.randomUUID();

    private LocalUser accountUpdatedAt(Instant updatedAt, boolean enabled) {
        return LocalUser.rehydrate(id, "casey", "hash", "casey@example.com", "Casey",
                enabled, false, Set.of(GlobalRole.OPERATOR), Set.of(), null, "test",
                Instant.parse("2026-01-01T00:00:00Z"), updatedAt, 3L);
    }

    private LocalTokenValidator validatorFor(LocalUser user) {
        return new LocalTokenValidator(new StubRepository(user, id));
    }

    @Test
    void tokenIssuedAfterTheLastChangeIsGood() {
        LocalTokenValidator validator = validatorFor(accountUpdatedAt(CHANGED, true));
        assertThat(validator.isStillValid(id, CHANGED.plusSeconds(5))).isTrue();
    }

    @Test
    void tokenIssuedBeforeTheLastChangeIsSpent() {
        // Role change, password reset, forced logout -- all move updatedAt forward, and a token
        // from before it must stop working.
        LocalTokenValidator validator = validatorFor(accountUpdatedAt(CHANGED, true));
        assertThat(validator.isStillValid(id, CHANGED.minusSeconds(60))).isFalse();
    }

    @Test
    void aDisabledAccountHonoursNoToken() {
        LocalTokenValidator validator = validatorFor(accountUpdatedAt(CHANGED, false));
        assertThat(validator.isStillValid(id, CHANGED.plusSeconds(3600))).isFalse();
    }

    @Test
    void aDeletedAccountHonoursNoToken() {
        LocalTokenValidator validator = new LocalTokenValidator(new StubRepository(null, id));
        assertThat(validator.isStillValid(id, CHANGED.plusSeconds(3600))).isFalse();
    }

    @Test
    void aTokenMintedInTheSameSecondAsTheChangeSurvivesViaTheGrace() {
        // iat is whole seconds; updatedAt has sub-second precision. Without the grace, a token
        // issued the same second it was updated would floor to just before it and be rejected
        // the instant it was minted.
        LocalUser user = accountUpdatedAt(Instant.parse("2026-08-30T10:00:00.700Z"), true);
        LocalTokenValidator validator = validatorFor(user);
        assertThat(validator.isStillValid(id, Instant.parse("2026-08-30T10:00:00Z"))).isTrue();
    }

    @Test
    void aMissingIssueTimeIsNotTrusted() {
        LocalTokenValidator validator = validatorFor(accountUpdatedAt(CHANGED, true));
        assertThat(validator.isStillValid(id, null)).isFalse();
    }

    /** Just enough repository to answer findById; the validator uses nothing else. */
    private record StubRepository(LocalUser user, UUID id) implements LocalUserRepository {
        @Override public Optional<LocalUser> findById(UUID lookup) {
            return user != null && lookup.equals(id) ? Optional.of(user) : Optional.empty();
        }
        @Override public LocalUser save(LocalUser u) { return u; }
        @Override public Optional<LocalUser> findByUsername(String username) { return Optional.empty(); }
        @Override public boolean existsByUsername(String username) { return false; }
        @Override public List<LocalUser> findAll() { return List.of(); }
        @Override public long count() { return user == null ? 0 : 1; }
        @Override public void deleteById(UUID lookup) { }
    }
}
