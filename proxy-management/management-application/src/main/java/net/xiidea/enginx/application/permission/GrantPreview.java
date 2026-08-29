package net.xiidea.enginx.application.permission;

import java.util.List;
import java.util.UUID;

/**
 * What a grant would actually confer, before anyone commits to it.
 *
 * <p>Closes the first half of R5. {@code *.example.com} at MANAGE reads like one line of
 * configuration and is in fact authority over every subdomain that exists — and, silently, every
 * subdomain created afterwards. The person granting it has almost never enumerated that set, and
 * the audit trail records only the pattern, so nobody discovers the true reach until it matters.
 *
 * @param matched            the sites the scope reaches today, capped for display
 * @param totalMatched       how many it reaches in total, which may exceed the list
 * @param truncated          whether {@code matched} is a partial list
 * @param newlyVisibleToSubject how many of those the subject cannot already reach by some other
 *                           grant. This is the number that answers "what does this actually add",
 *                           and it is usually the surprising one
 * @param coversFutureDomains whether the scope admits domains that do not exist yet — true for a
 *                           wildcard pattern, a group, or global. The honest caveat on a preview
 *                           of a rule that keeps applying
 */
public record GrantPreview(
        List<MatchedSite> matched,
        long totalMatched,
        boolean truncated,
        long newlyVisibleToSubject,
        boolean coversFutureDomains) {

    public record MatchedSite(UUID id, String domain, String name, boolean alreadyReachable) {
    }
}
