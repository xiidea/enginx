package net.xiidea.enginx.domain.certificate;

import net.xiidea.enginx.domain.shared.ValidationException;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What to ask a certificate authority for.
 *
 * @param domains the names to cover; the first is the subject, the rest are additional SANs
 */
public record CertificateRequest(String name, Set<String> domains) {

    private static final int MAX_DOMAINS = 100;

    public CertificateRequest {
        if (name == null || name.isBlank()) {
            throw new ValidationException("name", "A certificate needs a name");
        }
        name = name.trim();

        if (domains == null || domains.isEmpty()) {
            throw new ValidationException("domains", "A certificate must cover at least one domain");
        }
        Set<String> normalised = new LinkedHashSet<>();
        for (String domain : domains) {
            normalised.add(validated(domain));
        }
        if (normalised.size() > MAX_DOMAINS) {
            throw new ValidationException("domains", "A certificate may cover at most " + MAX_DOMAINS + " domains");
        }
        domains = Set.copyOf(normalised);
    }

    public static CertificateRequest of(String name, List<String> domains) {
        return new CertificateRequest(name, new LinkedHashSet<>(domains));
    }

    /**
     * Accepts a domain or a single leading wildcard.
     *
     * <p>Validated here rather than only at the provider, because an unvalidated name reaches an
     * external authority and, later, an NGINX {@code server_name}.
     */
    private static String validated(String domain) {
        if (domain == null || domain.isBlank()) {
            throw new ValidationException("domains", "A domain must not be blank");
        }
        String candidate = domain.trim().toLowerCase(Locale.ROOT);
        String base = candidate.startsWith("*.") ? candidate.substring(2) : candidate;

        // Reuse the proxy site rules: the same characters are unsafe wherever a name ends up.
        net.xiidea.enginx.domain.shared.DomainName.of(base);
        return candidate;
    }

    public boolean hasWildcard() {
        return domains.stream().anyMatch(domain -> domain.startsWith("*."));
    }
}
