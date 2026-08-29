package net.xiidea.enginx.application.group;

import java.util.UUID;

public final class DomainGroupCommands {

    private DomainGroupCommands() {
    }

    /**
     * @param slug     the path segment; the full path is derived from the parent so it cannot be
     *                 forged by the caller
     * @param parentId null for a top-level group
     */
    public record Create(String name, String slug, String description, UUID parentId) {
    }
}
