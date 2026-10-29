package net.xiidea.enginx.domain.proxy;

import net.xiidea.enginx.domain.shared.ValidationException;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Who may reach a site.
 *
 * <p>Applies to the whole site. The users are kept sorted by name, so the htpasswd file rendered
 * from them is the same bytes for the same set however they were entered, and an unchanged list
 * redeploys as a no-op.
 */
public record SiteProtection(ProtectionMode mode, List<BasicAuthUser> basicUsers, EmailOtpPolicy emailOtp,
                             OidcAccessPolicy oidc) {

    public static final int MAX_BASIC_USERS = 64;

    private static final SiteProtection NONE = new SiteProtection(ProtectionMode.NONE, List.of(), null, null);

    public SiteProtection {
        if (mode == null) {
            throw new ValidationException("protection.mode", "Protection mode is required");
        }
        basicUsers = basicUsers == null ? List.of() : basicUsers.stream()
                .sorted(Comparator.comparing(BasicAuthUser::username))
                .toList();

        if (mode == ProtectionMode.BASIC) {
            if (basicUsers.isEmpty()) {
                throw new ValidationException("protection.basicUsers",
                        "Basic authentication needs at least one user");
            }
            if (basicUsers.size() > MAX_BASIC_USERS) {
                throw new ValidationException("protection.basicUsers",
                        "A site may not have more than " + MAX_BASIC_USERS + " basic authentication users");
            }
            Set<String> seen = new HashSet<>();
            for (BasicAuthUser user : basicUsers) {
                if (!seen.add(user.username())) {
                    throw new ValidationException("protection.basicUsers",
                            "Duplicate basic authentication user " + user.username());
                }
            }
        } else if (!basicUsers.isEmpty()) {
            // Kept users with protection off would come back into force the moment someone
            // re-enabled it, with passwords nobody remembers setting.
            throw new ValidationException("protection.basicUsers",
                    "Basic authentication users can only be set when the mode is BASIC");
        }

        if (mode == ProtectionMode.EMAIL_OTP && emailOtp == null) {
            throw new ValidationException("protection.emailOtp",
                    "Email sign-in needs an allow-list of addresses");
        }
        if (mode != ProtectionMode.EMAIL_OTP && emailOtp != null) {
            throw new ValidationException("protection.emailOtp",
                    "An email allow-list can only be set when the mode is EMAIL_OTP");
        }
        if (mode == ProtectionMode.OIDC && oidc == null) {
            throw new ValidationException("protection.oidc",
                    "Identity provider sign-in needs to say who may enter");
        }
        if (mode != ProtectionMode.OIDC && oidc != null) {
            throw new ValidationException("protection.oidc",
                    "An identity provider access policy can only be set when the mode is OIDC");
        }
    }

    public SiteProtection(ProtectionMode mode, List<BasicAuthUser> basicUsers) {
        this(mode, basicUsers, null, null);
    }

    public SiteProtection(ProtectionMode mode, List<BasicAuthUser> basicUsers, EmailOtpPolicy emailOtp) {
        this(mode, basicUsers, emailOtp, null);
    }

    /** How long a gate sign-in lasts, for either gate mode. */
    public java.time.Duration sessionTtl() {
        if (emailOtp != null) {
            return emailOtp.sessionTtl();
        }
        return oidc == null ? null : oidc.sessionTtl();
    }

    public static SiteProtection none() {
        return NONE;
    }

    public static SiteProtection basic(List<BasicAuthUser> users) {
        return new SiteProtection(ProtectionMode.BASIC, users, null);
    }

    public static SiteProtection emailOtp(EmailOtpPolicy policy) {
        return new SiteProtection(ProtectionMode.EMAIL_OTP, List.of(), policy, null);
    }

    public static SiteProtection oidc(OidcAccessPolicy policy) {
        return new SiteProtection(ProtectionMode.OIDC, List.of(), null, policy);
    }

    public boolean isProtected() {
        return mode != ProtectionMode.NONE;
    }

    /** The htpasswd file for {@link ProtectionMode#BASIC}: one {@code user:hash} line per user. */
    public String htpasswd() {
        StringBuilder out = new StringBuilder();
        for (BasicAuthUser user : basicUsers) {
            out.append(user.username()).append(':').append(user.passwordHash()).append('\n');
        }
        return out.toString();
    }
}
