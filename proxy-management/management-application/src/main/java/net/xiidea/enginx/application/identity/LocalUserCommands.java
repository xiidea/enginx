package net.xiidea.enginx.application.identity;

import net.xiidea.enginx.domain.permission.GlobalRole;

import java.util.Set;
import java.util.UUID;

public final class LocalUserCommands {

    private LocalUserCommands() {
    }

    public record Create(String username, String password, String email, String displayName,
                         Set<GlobalRole> roles, Set<String> groupPaths, boolean mustChangePassword) {
    }

    public record Update(UUID id, String email, String displayName,
                         Set<GlobalRole> roles, Set<String> groupPaths) {
    }

    /**
     * @param currentPassword required when a user changes their own password, and deliberately not
     *                        accepted when an administrator resets someone else's — an admin who
     *                        needed the old password could not perform a reset at all
     */
    public record ChangePassword(UUID id, String currentPassword, String newPassword) {
    }
}
