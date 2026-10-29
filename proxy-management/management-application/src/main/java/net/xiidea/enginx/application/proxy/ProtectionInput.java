package net.xiidea.enginx.application.proxy;

import net.xiidea.enginx.domain.proxy.ProtectionMode;

import java.util.List;

/**
 * The protection a caller asked for, before passwords are hashed.
 *
 * <p>Passwords are write-only: a site is returned with its usernames and never their hashes, so a
 * client editing a site sends a user without a password to mean "keep the one it has".
 */
public record ProtectionInput(ProtectionMode mode, List<BasicUser> basicUsers, EmailOtp emailOtp) {

    public ProtectionInput {
        basicUsers = basicUsers == null ? List.of() : List.copyOf(basicUsers);
    }

    public ProtectionInput(ProtectionMode mode, List<BasicUser> basicUsers) {
        this(mode, basicUsers, null);
    }

    /**
     * @param sessionTtlSeconds how long a sign-in lasts, or null for the default
     */
    public record EmailOtp(List<String> allowedEmails, Integer sessionTtlSeconds) {

        public EmailOtp {
            allowedEmails = allowedEmails == null ? List.of() : List.copyOf(allowedEmails);
        }
    }

    /**
     * @param password the new password, or null to keep the user's current one
     */
    public record BasicUser(String username, String password) {

        @Override
        public String toString() {
            return "BasicUser[username=" + username + ", password=" + (password == null ? "unchanged" : "<redacted>") + "]";
        }
    }
}
