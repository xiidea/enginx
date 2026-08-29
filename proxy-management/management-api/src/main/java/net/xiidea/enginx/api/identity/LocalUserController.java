package net.xiidea.enginx.api.identity;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import net.xiidea.enginx.application.identity.LocalUserCommands;
import net.xiidea.enginx.application.identity.LocalUserService;
import net.xiidea.enginx.domain.identity.LocalUser;
import net.xiidea.enginx.domain.permission.GlobalRole;
import net.xiidea.enginx.domain.shared.ValidationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Administering local accounts.
 *
 * <p>No response ever carries a password hash. The aggregate holds one, but it leaves the
 * application only through the login path, and there only to be compared.
 */
@RestController
@RequestMapping("/api/v1/local-users")
@Tag(name = "Local users", description = "Accounts the platform authenticates itself")
public class LocalUserController {

    private final LocalUserService users;

    public LocalUserController(LocalUserService users) {
        this.users = users;
    }

    @GetMapping
    @Operation(summary = "List local accounts")
    public List<Response> list() {
        return users.findAll().stream().map(LocalUserController::toResponse).toList();
    }

    @GetMapping("/{id}")
    @Operation(summary = "One local account")
    public Response get(@PathVariable UUID id) {
        return toResponse(users.get(id));
    }

    @PostMapping
    @Operation(summary = "Create a local account",
            description = "Requires global admin: a local account carries global roles, so anyone "
                    + "able to create one could otherwise grant themselves SUPER_ADMIN.")
    public ResponseEntity<Response> create(@Valid @RequestBody CreateRequest request,
                                           UriComponentsBuilder uriBuilder) {
        LocalUser created = users.create(new LocalUserCommands.Create(
                request.username(), request.password(), request.email(), request.displayName(),
                parseRoles(request.roles()), request.groupPaths() == null ? Set.of()
                        : Set.copyOf(request.groupPaths()),
                request.mustChangePassword()));

        URI location = uriBuilder.path("/api/v1/local-users/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(toResponse(created));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a local account's profile, roles and groups")
    public Response update(@PathVariable UUID id, @Valid @RequestBody UpdateRequest request) {
        return toResponse(users.update(new LocalUserCommands.Update(id, request.email(),
                request.displayName(), parseRoles(request.roles()),
                request.groupPaths() == null ? Set.of() : Set.copyOf(request.groupPaths()))));
    }

    @PostMapping("/{id}/enable")
    @Operation(summary = "Enable a local account")
    public Response enable(@PathVariable UUID id) {
        return toResponse(users.setEnabled(id, true));
    }

    @PostMapping("/{id}/disable")
    @Operation(summary = "Disable a local account",
            description = "Refused for the last enabled administrator, which would lock everyone out.")
    public Response disable(@PathVariable UUID id) {
        return toResponse(users.setEnabled(id, false));
    }

    @PutMapping("/{id}/password")
    @Operation(summary = "Change a password",
            description = "Changing your own requires the current password. An administrator "
                    + "resetting someone else's does not, and must not — a reset exists precisely "
                    + "for the case where the old password is unavailable.")
    public ResponseEntity<Void> changePassword(@PathVariable UUID id,
                                                @Valid @RequestBody ChangePasswordRequest request) {
        users.changePassword(new LocalUserCommands.ChangePassword(
                id, request.currentPassword(), request.newPassword()));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a local account",
            description = "Refused for the last enabled administrator.")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        users.delete(id);
        return ResponseEntity.noContent().build();
    }

    private static Set<GlobalRole> parseRoles(List<String> roles) {
        if (roles == null) {
            return Set.of();
        }
        return roles.stream().map(role -> GlobalRole.from(role.trim().toUpperCase(Locale.ROOT))
                        .orElseThrow(() -> new ValidationException("roles",
                                "'" + role + "' is not a role. Expected one of: SUPER_ADMIN, ADMIN, "
                                        + "OPERATOR, READ_ONLY")))
                .collect(Collectors.toSet());
    }

    private static Response toResponse(LocalUser user) {
        return new Response(user.id(), user.username(), user.email(), user.displayName(),
                user.enabled(), user.mustChangePassword(),
                user.roles().stream().map(Enum::name).sorted().toList(),
                user.groupPaths().stream().sorted().toList(),
                user.lastLoginAt(), user.createdBy(), user.createdAt(), user.version());
    }

    @Schema(name = "LocalUserResponse",
            requiredProperties = {"id", "username", "enabled", "mustChangePassword", "roles",
                    "groupPaths", "createdBy", "createdAt", "version"})
    public record Response(UUID id, String username, String email, String displayName,
                           boolean enabled, boolean mustChangePassword, List<String> roles,
                           List<String> groupPaths, Instant lastLoginAt, String createdBy,
                           Instant createdAt, long version) {
    }

    @Schema(name = "CreateLocalUserRequest", requiredProperties = {"username", "password"})
    public record CreateRequest(
            @NotBlank(message = "A username is required") @Size(max = 128) String username,
            @NotBlank(message = "A password is required") @Size(max = 256) String password,
            @Size(max = 256) String email,
            @Size(max = 256) String displayName,
            List<String> roles,
            List<String> groupPaths,
            boolean mustChangePassword) {
    }

    @Schema(name = "UpdateLocalUserRequest")
    public record UpdateRequest(@Size(max = 256) String email, @Size(max = 256) String displayName,
                                List<String> roles, List<String> groupPaths) {
    }

    @Schema(name = "ChangePasswordRequest", requiredProperties = {"newPassword"})
    public record ChangePasswordRequest(String currentPassword,
                                        @NotBlank(message = "A new password is required")
                                        @Size(max = 256) String newPassword) {
    }
}
