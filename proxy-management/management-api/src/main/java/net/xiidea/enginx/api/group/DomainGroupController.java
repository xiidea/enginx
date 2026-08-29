package net.xiidea.enginx.api.group;

import net.xiidea.enginx.api.group.dto.DomainGroupDtos;
import net.xiidea.enginx.application.group.DomainGroupCommands;
import net.xiidea.enginx.application.group.DomainGroupService;
import net.xiidea.enginx.application.permission.SitePermissionService;
import net.xiidea.enginx.domain.group.DomainGroup;
import net.xiidea.enginx.domain.group.DomainGroupRepository;
import net.xiidea.enginx.domain.permission.PermissionLevel;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/domain-groups")
@Tag(name = "Domain groups", description = "Grouping proxy sites so permissions can be granted over a set")
public class DomainGroupController {

    private final DomainGroupService service;
    private final DomainGroupRepository groups;
    private final SitePermissionService permissions;

    public DomainGroupController(DomainGroupService service,
                                 DomainGroupRepository groups,
                                 SitePermissionService permissions) {
        this.service = service;
        this.groups = groups;
        this.permissions = permissions;
    }

    @GetMapping
    @Operation(summary = "List domain groups visible to you")
    public List<DomainGroupDtos.Response> list() {
        return service.findVisible().stream().map(this::toResponse).toList();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch one domain group")
    public DomainGroupDtos.Response get(@PathVariable UUID id) {
        return toResponse(service.get(id));
    }

    @PostMapping
    @Operation(summary = "Create a domain group",
            description = "A top-level group requires global MANAGE; a subgroup requires MANAGE on its parent.")
    public ResponseEntity<DomainGroupDtos.Response> create(@Valid @RequestBody DomainGroupDtos.CreateRequest request,
                                                           UriComponentsBuilder uriBuilder) {
        DomainGroup created = service.create(new DomainGroupCommands.Create(
                request.name(), request.slug(), request.description(), request.parentId()));

        URI location = uriBuilder.path("/api/v1/domain-groups/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(toResponse(created));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Rename a domain group",
            description = "The path is identity and cannot be changed: moving a group would silently change "
                    + "which sites every grant beneath it reaches.")
    public DomainGroupDtos.Response update(@PathVariable UUID id,
                                            @Valid @RequestBody DomainGroupDtos.UpdateRequest request) {
        return toResponse(service.rename(id, request.name(), request.description()));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete an empty domain group",
            description = "Refused while the group has subgroups or members, because deleting it would "
                    + "silently revoke every grant made over it.")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/members")
    @Operation(summary = "List the sites in a group")
    public List<UUID> members(@PathVariable UUID id) {
        service.get(id);
        return List.copyOf(groups.memberSiteIds(id));
    }

    @PostMapping("/{id}/members/{siteId}")
    @Operation(summary = "Add a site to a group",
            description = "Requires MANAGE on both the group and the site. Requiring only the group would let "
                    + "someone add a site they have no rights to and inherit control of it.")
    public ResponseEntity<Void> addMember(@PathVariable UUID id, @PathVariable UUID siteId) {
        service.addMember(id, siteId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}/members/{siteId}")
    @Operation(summary = "Remove a site from a group")
    public ResponseEntity<Void> removeMember(@PathVariable UUID id, @PathVariable UUID siteId) {
        service.removeMember(id, siteId);
        return ResponseEntity.noContent().build();
    }

    private DomainGroupDtos.Response toResponse(DomainGroup group) {
        PermissionLevel level = permissions.effectiveGroupLevel(group);
        return new DomainGroupDtos.Response(
                group.id(),
                group.parentId(),
                group.name(),
                group.path().value(),
                group.path().depth(),
                group.description(),
                groups.countMembers(group.id()),
                level == null ? null : level.name(),
                group.createdBy(),
                group.createdAt(),
                group.version());
    }
}
