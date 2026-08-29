package net.xiidea.enginx.api.identity;

import net.xiidea.enginx.api.common.PageResponse;
import net.xiidea.enginx.api.permission.dto.PermissionDtos;
import net.xiidea.enginx.application.permission.SitePermissionService;
import net.xiidea.enginx.application.shared.IdentityMirror;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Candidate subjects for a permission grant.
 *
 * <p>Reads the mirror of users and groups that have been seen in tokens, so an operator can grant
 * by name rather than by pasting a subject claim. Gated on administering at least one scope: a
 * group administrator needs this to delegate within their own group, so requiring global ADMIN
 * would defeat the point of scoped administration, while leaving it open would publish the
 * user list to anyone with a token.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Identity", description = "Users and groups available as permission subjects")
public class IdentityController {

    private final IdentityMirror mirror;
    private final SitePermissionService permissions;

    public IdentityController(IdentityMirror mirror, SitePermissionService permissions) {
        this.mirror = mirror;
        this.permissions = permissions;
    }

    /** Enough for a dropdown to feel instant; more than a person reads before typing. */
    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;

    @GetMapping("/users")
    @Operation(summary = "Search users who have signed in",
            description = "A convenience index for grant authoring. Authorization never reads it: "
                    + "roles and group membership always come from the caller's token. Paged and "
                    + "searchable because this gains a row for every person who ever signs in and "
                    + "loses one only when the platform deletes an account it owns.")
    public PageResponse<PermissionDtos.SubjectResponse> users(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_SIZE) int size) {

        permissions.requireAnyAdminScope();

        return PageResponse.from(
                mirror.findUsers(search, Math.max(page, 0), Math.clamp(size, 1, MAX_SIZE)),
                user -> new PermissionDtos.SubjectResponse("USER", user.subject(),
                        user.displayName() == null ? user.username() : user.displayName(),
                        user.username(), user.present()));
    }

    @GetMapping("/groups")
    @Operation(summary = "List Keycloak groups seen in tokens")
    public List<PermissionDtos.SubjectResponse> groups() {
        permissions.requireAnyAdminScope();
        return mirror.listGroups().stream()
                .map(group -> new PermissionDtos.SubjectResponse("GROUP", group.path(), group.name()))
                .toList();
    }
}
