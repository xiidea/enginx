package net.xiidea.enginx.domain.group;

import net.xiidea.enginx.domain.shared.ConflictException;
import net.xiidea.enginx.domain.shared.ValidationException;

import java.time.Instant;
import java.util.UUID;

/**
 * A named set of proxy sites that permissions can be granted over.
 *
 * <p>Groups nest, and a grant on a parent reaches every site filed anywhere beneath it. That is
 * the point of the hierarchy: "the platform team manages everything under Production" should be
 * one grant, not one per subgroup that someone must remember to add later.
 */
public final class DomainGroup {

    private final UUID id;
    private final UUID parentId;
    private String name;
    private final GroupPath path;
    private String description;
    private final String createdBy;
    private final Instant createdAt;
    private Instant updatedAt;
    private final long version;

    private DomainGroup(UUID id, UUID parentId, String name, GroupPath path, String description,
                        String createdBy, Instant createdAt, Instant updatedAt, long version) {
        this.id = id;
        this.parentId = parentId;
        this.name = name;
        this.path = path;
        this.description = description;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    public static DomainGroup createRoot(UUID id, String name, String slug, String description,
                                         String actor, Instant now) {
        return new DomainGroup(id, null, validName(name), GroupPath.root(slug), description,
                actor, now, now, 0L);
    }

    public static DomainGroup createChild(UUID id, DomainGroup parent, String name, String slug,
                                          String description, String actor, Instant now) {
        if (parent == null) {
            throw new ValidationException("parentId", "Parent group not supplied");
        }
        GroupPath childPath = parent.path.child(slug);
        if (childPath.depth() > GroupPath.MAX_DEPTH) {
            throw new ValidationException("parentId",
                    "Group nesting may not exceed " + GroupPath.MAX_DEPTH + " levels");
        }
        return new DomainGroup(id, parent.id, validName(name), childPath, description, actor, now, now, 0L);
    }

    public static DomainGroup rehydrate(UUID id, UUID parentId, String name, GroupPath path, String description,
                                        String createdBy, Instant createdAt, Instant updatedAt, long version) {
        return new DomainGroup(id, parentId, name, path, description, createdBy, createdAt, updatedAt, version);
    }

    /**
     * Renaming changes the label only. The path is identity: moving a group would rewrite every
     * descendant's path and silently change which sites every grant beneath it reaches, so a move
     * is not offered as an edit.
     */
    public void rename(String newName, String newDescription, Instant now) {
        this.name = validName(newName);
        this.description = newDescription;
        this.updatedAt = now;
    }

    public void requireNoChildren(boolean hasChildren) {
        if (hasChildren) {
            throw new ConflictException("Delete or move the subgroups of '" + name + "' before deleting it");
        }
    }

    public void requireNoMembers(long memberCount) {
        if (memberCount > 0) {
            throw new ConflictException(
                    "'" + name + "' still contains " + memberCount + " site(s). Remove them before deleting it.");
        }
    }

    private static String validName(String name) {
        if (name == null || name.isBlank()) {
            throw new ValidationException("name", "Group name must not be blank");
        }
        String trimmed = name.trim();
        if (trimmed.length() > 128) {
            throw new ValidationException("name", "Group name must not exceed 128 characters");
        }
        return trimmed;
    }

    public UUID id() {
        return id;
    }

    public UUID parentId() {
        return parentId;
    }

    public String name() {
        return name;
    }

    public GroupPath path() {
        return path;
    }

    public String description() {
        return description;
    }

    public String createdBy() {
        return createdBy;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public long version() {
        return version;
    }
}
