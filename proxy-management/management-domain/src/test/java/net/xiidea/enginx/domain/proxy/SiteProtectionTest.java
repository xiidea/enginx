package net.xiidea.enginx.domain.proxy;

import net.xiidea.enginx.domain.shared.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SiteProtectionTest {

    private static final String HASH =
            "$6$fixturesalt0001$nTLkJQUNn08kFVHCPBxOSfCbqF3lA60ZVOdyzJeqdQv4MeAjTOPRztNt1OcbBALrB.AcE2mRt.mh8cHpEl2UD1";

    @ParameterizedTest
    @DisplayName("a username that could split or add an htpasswd line is refused")
    @ValueSource(strings = {"ali:ce", "ali\nce", "ali ce", "ali\"ce", "", "a;b", "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"})
    void unsafeUsernamesAreRefused(String username) {
        assertThatThrownBy(() -> new BasicAuthUser(username, HASH))
                .isInstanceOf(ValidationException.class);
    }

    @ParameterizedTest
    @DisplayName("anything but a SHA-512-crypt hash is refused, plaintext above all")
    @ValueSource(strings = {"secret", "$2a$12$abcdefghijklmnopqrstuuJ8nq3e7LZtqgUq0z3cNRqxG0hHk7G7a",
            "$6$salt$short", "$6$fixturesalt0001$nTLkJQUNn08kFVHCPBxOSfCbqF3lA60ZVOdyzJeqdQv4MeAjTOPRztNt1OcbBALrB.AcE2mRt.mh8cHpEl2UD1\nmallory:x"})
    void nonHashesAreRefused(String hash) {
        assertThatThrownBy(() -> new BasicAuthUser("alice", hash))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void theHashIsNeverPrinted() {
        assertThat(new BasicAuthUser("alice", HASH).toString()).doesNotContain(HASH);
    }

    @Test
    void basicNeedsAtLeastOneUser() {
        assertThatThrownBy(() -> SiteProtection.basic(List.of()))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("at least one user");
    }

    @Test
    void duplicateUsersAreRefused() {
        assertThatThrownBy(() -> SiteProtection.basic(List.of(
                new BasicAuthUser("alice", HASH), new BasicAuthUser("alice", HASH))))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Duplicate");
    }

    @Test
    @DisplayName("users cannot linger on an unprotected site, to come back with a forgotten password")
    void usersRequireBasicMode() {
        assertThatThrownBy(() -> new SiteProtection(ProtectionMode.NONE, List.of(new BasicAuthUser("alice", HASH))))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void htpasswdIsSortedByUsername() {
        SiteProtection protection = SiteProtection.basic(List.of(
                new BasicAuthUser("zed", HASH), new BasicAuthUser("alice", HASH)));

        assertThat(protection.htpasswd()).isEqualTo("alice:" + HASH + "\nzed:" + HASH + "\n");
    }

    @ParameterizedTest
    @DisplayName("allow-list entries that are not plain addresses or *@domain are refused")
    @ValueSource(strings = {"alice", "a\"b@example.com", "a b@example.com", "*@*.example.com", "alice@example",
            "alice@exa mple.com", "*@", "a@b.c\n"})
    void unsafeAllowedEmailsAreRefused(String email) {
        assertThatThrownBy(() -> new AllowedEmail(email)).isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("an exact entry admits that address, a wildcard every address at exactly that domain")
    void allowListMatching() {
        EmailOtpPolicy policy = new EmailOtpPolicy(List.of(new AllowedEmail("Alice@Partner.org"),
                new AllowedEmail("*@example.com")), null);

        assertThat(policy.admits("alice@partner.org")).isTrue();
        assertThat(policy.admits(" ALICE@partner.org ")).isTrue();
        assertThat(policy.admits("bob@partner.org")).isFalse();
        assertThat(policy.admits("bob@example.com")).isTrue();
        assertThat(policy.admits("bob@evil-example.com")).isFalse();
        assertThat(policy.admits("bob@sub.example.com")).isFalse();
        assertThat(policy.admits("bob@example.com.evil.org")).isFalse();
    }

    @Test
    void sessionLengthIsBounded() {
        List<AllowedEmail> one = List.of(new AllowedEmail("alice@example.com"));
        assertThatThrownBy(() -> new EmailOtpPolicy(one, java.time.Duration.ofMinutes(1)))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new EmailOtpPolicy(one, java.time.Duration.ofDays(31)))
                .isInstanceOf(ValidationException.class);
        assertThat(new EmailOtpPolicy(one, null).sessionTtl()).isEqualTo(EmailOtpPolicy.DEFAULT_SESSION_TTL);
    }

    @Test
    void emailSignInNeedsAPolicyAndOnlyThatModeTakesOne() {
        EmailOtpPolicy policy = new EmailOtpPolicy(List.of(new AllowedEmail("alice@example.com")), null);
        assertThatThrownBy(() -> new SiteProtection(ProtectionMode.EMAIL_OTP, List.of(), null))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new SiteProtection(ProtectionMode.NONE, List.of(), policy))
                .isInstanceOf(ValidationException.class);
    }
}
