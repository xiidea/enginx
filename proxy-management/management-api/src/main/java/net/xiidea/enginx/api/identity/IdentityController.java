package net.xiidea.enginx.api.identity;

import net.xiidea.enginx.api.common.PageResponse;
import net.xiidea.enginx.api.permission.dto.PermissionDtos;
import net.xiidea.enginx.application.identity.IdentityDirectoryService;
import net.xiidea.enginx.application.permission.SitePermissionService;
import net.xiidea.enginx.application.shared.IdentityMirror;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    private final IdentityDirectoryService directory;

    public IdentityController(IdentityMirror mirror, SitePermissionService permissions,
                              IdentityDirectoryService directory) {
        this.mirror = mirror;
        this.permissions = permissions;
        this.directory = directory;
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
    public PageResponse<DirectoryEntryResponse> users(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "false") boolean stale,
            @RequestParam(required = false) Integer dormantForDays,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_SIZE) int size) {

        return PageResponse.from(
                directory.find(new IdentityDirectoryService.Query(search, stale, dormantForDays,
                        Math.max(page, 0), Math.clamp(size, 1, MAX_SIZE))),
                IdentityController::toEntry);
    }

    @DeleteMapping("/users/{subjectRef}")
    @Operation(summary = "Remove one subject from the directory",
            description = "Removes a name the console can offer when authoring a grant. It does "
                    + "not revoke access and does not delete an account: a subject removed by "
                    + "mistake reappears the next time its owner signs in. Requires global admin, "
                    + "because the directory is shared.")
    public ResponseEntity<Void> forget(@PathVariable String subjectRef) {
        directory.forget(subjectRef);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/users/cleanup")
    @Operation(summary = "Remove every stale subject",
            description = "Stale means a local subject whose account no longer exists or, with "
                    + "dormantForDays, one not seen for that long. Entries a permission grant "
                    + "still names are skipped unless includeGranted is set: removing one leaves "
                    + "the grant showing a reference nobody can identify, which is worse than an "
                    + "untidy picker. Grants are never revoked here.")
    public CleanupResponse cleanup(@RequestBody(required = false) CleanupRequest request) {
        CleanupRequest command = request == null ? new CleanupRequest(null, null) : request;
        IdentityDirectoryService.Result result = directory.cleanup(new IdentityDirectoryService.Command(
                command.dormantForDays(), Boolean.TRUE.equals(command.includeGranted())));

        return new CleanupResponse(result.removed(), result.skippedBecauseGranted(),
                result.removedSubjects());
    }

    private static DirectoryEntryResponse toEntry(IdentityDirectoryService.Entry entry) {
        IdentityMirror.MirroredUser user = entry.user();
        return new DirectoryEntryResponse("USER", user.subject(),
                user.displayName() == null ? user.username() : user.displayName(),
                user.username(), user.present(), user.lastLoginAt(), entry.hasGrants());
    }

    /**
     * @param present    false for a subject the platform knows no longer resolves to an account
     * @param hasGrants  whether a permission grant still names it, which is why cleanup skips it
     */
    @Schema(name = "DirectoryEntryResponse", description = "Someone who has signed in.",
            requiredProperties = {"subjectType", "subjectRef", "displayName", "present", "hasGrants"})
    public record DirectoryEntryResponse(String subjectType, String subjectRef, String displayName,
                                          String username, boolean present,
                                          java.time.Instant lastLoginAt, boolean hasGrants) {
    }

    /**
     * Both boxed, and neither required.
     *
     * <p>A primitive component makes the field mandatory in practice: Jackson refuses a body that
     * omits it, so {@code {}} — a request meaning "use the defaults" — would be rejected as
     * malformed even though the schema marks nothing as required.
     */
    @Schema(name = "DirectoryCleanupRequest")
    public record CleanupRequest(Integer dormantForDays, Boolean includeGranted) {
    }

    @Schema(name = "DirectoryCleanupResponse",
            requiredProperties = {"removed", "skippedBecauseGranted", "removedSubjects"})
    public record CleanupResponse(int removed, int skippedBecauseGranted, List<String> removedSubjects) {
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
