package net.xiidea.enginx.domain.permission;

import net.xiidea.enginx.domain.shared.DomainName;
import net.xiidea.enginx.domain.shared.ValidationException;

import java.util.Locale;

/**
 * A domain or wildcard a grant applies to, for example {@code app.example.com} or
 * {@code *.test.example.com}.
 *
 * <p>The matching form is the dot-reversed pattern, because a suffix match cannot use a B-tree
 * index but a prefix match can. {@code *.test.example.com} becomes {@code com.example.test.} and
 * a site matches when its reversed domain starts with that; an exact pattern becomes
 * {@code com.example.app} and matches on equality. The trailing dot is what keeps a wildcard
 * from matching its own apex: {@code test.example.com} reverses to {@code com.example.test},
 * which does not start with {@code com.example.test.}.
 */
public record DomainPattern(String value, String reversedPrefix, boolean wildcard) {

    public static DomainPattern of(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ValidationException("domainPattern", "A domain pattern is required");
        }
        String normalised = raw.trim().toLowerCase(Locale.ROOT);

        if (!normalised.startsWith("*.")) {
            DomainName exact = DomainName.of(normalised);
            return new DomainPattern(exact.value(), exact.reversed(), false);
        }

        String base = normalised.substring(2);
        if (base.startsWith("*")) {
            throw new ValidationException("domainPattern",
                    "Only a single leading wildcard is supported, for example *.test.example.com");
        }
        DomainName baseName = DomainName.of(base);
        return new DomainPattern("*." + baseName.value(), baseName.reversed() + ".", true);
    }

    public boolean matches(String siteDomainReversed) {
        if (siteDomainReversed == null) {
            return false;
        }
        return wildcard ? siteDomainReversed.startsWith(reversedPrefix) : siteDomainReversed.equals(reversedPrefix);
    }

    /**
     * Whether every domain this pattern admits is also admitted by {@code other}.
     *
     * <p>Used when granting: conferring authority over {@code *.test.example.com} is only
     * legitimate for someone who already holds authority over a namespace that contains it.
     * Comparing the reversed forms makes containment a prefix test, the same trick that makes
     * matching indexable.
     */
    public boolean isCoveredBy(DomainPattern other) {
        if (other.wildcard) {
            return reversedPrefix.startsWith(other.reversedPrefix);
        }
        // A non-wildcard pattern covers only itself, and never a wildcard, which is broader.
        return !wildcard && reversedPrefix.equals(other.reversedPrefix);
    }

    /** Rebuilds a stored pattern without re-validating; used by the persistence adapter. */
    public static DomainPattern rehydrate(String value, String reversedPrefix) {
        return new DomainPattern(value, reversedPrefix, reversedPrefix.endsWith("."));
    }

    @Override
    public String toString() {
        return value;
    }
}
