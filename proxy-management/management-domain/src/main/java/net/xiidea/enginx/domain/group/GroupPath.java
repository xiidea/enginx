package net.xiidea.enginx.domain.group;

import net.xiidea.enginx.domain.shared.ValidationException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A materialised path identifying a domain group, for example {@code production.eu.web}.
 *
 * <p>Storing the whole ancestry in the row turns both hierarchy questions into indexable string
 * operations: descendants are a prefix scan, and ancestors are computed by splitting the path,
 * which needs no query at all. That is why this is a plain column rather than a PostgreSQL
 * {@code ltree} — the extension buys nothing here and has to be installed with privileges a
 * managed database may not grant.
 */
public record GroupPath(String value) {

    public static final int MAX_DEPTH = 8;
    private static final Pattern SEGMENT = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?$");

    public GroupPath {
        if (value == null || value.isBlank()) {
            throw new ValidationException("path", "Group path must not be blank");
        }
        value = value.trim().toLowerCase(Locale.ROOT);
        String[] segments = value.split("\\.", -1);
        if (segments.length > MAX_DEPTH) {
            throw new ValidationException("path", "Group nesting may not exceed " + MAX_DEPTH + " levels");
        }
        for (String segment : segments) {
            if (!SEGMENT.matcher(segment).matches()) {
                throw new ValidationException("path",
                        "'" + segment + "' is not a valid group segment. Use lowercase letters, digits and hyphens.");
            }
        }
    }

    public static GroupPath root(String segment) {
        return new GroupPath(segment);
    }

    public GroupPath child(String segment) {
        return new GroupPath(value + "." + segment);
    }

    public boolean isDescendantOf(GroupPath ancestor) {
        return value.startsWith(ancestor.value + ".");
    }

    public int depth() {
        return value.split("\\.").length;
    }

    /** Every path from the root down to, but not including, this one. */
    public List<String> ancestorPaths() {
        String[] segments = value.split("\\.");
        List<String> ancestors = new ArrayList<>(segments.length - 1);
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < segments.length - 1; i++) {
            if (i > 0) {
                current.append('.');
            }
            current.append(segments[i]);
            ancestors.add(current.toString());
        }
        return ancestors;
    }

    /** The prefix that matches every descendant of this group in a LIKE scan. */
    public String descendantPrefix() {
        return value + ".";
    }

    @Override
    public String toString() {
        return value;
    }
}
