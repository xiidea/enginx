package net.xiidea.enginx.api.permission;

import net.xiidea.enginx.api.permission.dto.PermissionDtos;
import net.xiidea.enginx.application.permission.GrantPreview;
import net.xiidea.enginx.application.permission.PermissionCommands;
import net.xiidea.enginx.application.permission.PermissionGrantService;
import net.xiidea.enginx.application.permission.SitePermissionService;
import net.xiidea.enginx.application.proxy.ProxySiteService;
import net.xiidea.enginx.domain.permission.PermissionGrant;
import net.xiidea.enginx.domain.permission.PermissionLevel;
import net.xiidea.enginx.domain.permission.ScopeType;
import net.xiidea.enginx.domain.permission.SubjectType;
import net.xiidea.enginx.domain.proxy.ProxySite;
import net.xiidea.enginx.domain.shared.ValidationException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/permissions")
@Tag(name = "Permissions", description = "Domain-scoped permission grants")
public class PermissionController {

    private final PermissionGrantService grants;
    private final SitePermissionService permissions;
    private final ProxySiteService sites;
    private final Clock clock;

    public PermissionController(PermissionGrantService grants,
                                SitePermissionService permissions,
                                ProxySiteService sites,
                                Clock clock) {
        this.grants = grants;
        this.permissions = permissions;
        this.sites = sites;
        this.clock = clock;
    }

    @GetMapping
    @Operation(summary = "List permission grants",
            description = "Filter by scope with groupId or siteId. Listing every grant requires global ADMIN.")
    public List<PermissionDtos.Response> list(@RequestParam(required = false) UUID groupId,
                                              @RequestParam(required = false) UUID siteId) {
        if (groupId != null && siteId != null) {
            throw new ValidationException("scope", "Filter by groupId or siteId, not both");
        }
        List<PermissionGrant> found = groupId != null ? grants.findForGroup(groupId)
                : siteId != null ? grants.findForSite(siteId)
                : grants.findAll();

        return found.stream().map(this::toResponse).toList();
    }

    @PostMapping
    @Operation(summary = "Grant a permission",
            description = "Requires ADMIN on the target scope, and you may never grant more than you hold.")
    public ResponseEntity<PermissionDtos.Response> grant(@Valid @RequestBody PermissionDtos.GrantRequest request,
                                                          UriComponentsBuilder uriBuilder) {
        PermissionGrant granted = grants.grant(new PermissionCommands.Grant(
                parse(SubjectType.class, request.subjectType(), "subjectType"),
                request.subjectRef(),
                parse(ScopeType.class, request.scopeType(), "scopeType"),
                request.scopeGroupId(),
                request.scopeSiteId(),
                request.domainPattern(),
                parse(PermissionLevel.class, request.level(), "level"),
                request.expiresAt()));

        URI location = uriBuilder.path("/api/v1/permissions/{id}").buildAndExpand(granted.id()).toUri();
        return ResponseEntity.created(location).body(toResponse(granted));
    }

    @PostMapping("/preview")
    @Operation(summary = "Show what a grant would reach, without creating it",
            description = "Authorised exactly like creating the grant. Answers the question a "
                    + "wildcard pattern hides: '*.example.com' at MANAGE is one line of "
                    + "configuration and authority over every subdomain, including ones created "
                    + "later. The list is what the scope reaches today; coversFutureDomains says "
                    + "whether it will keep admitting new ones.")
    public PermissionDtos.PreviewResponse preview(@Valid @RequestBody PermissionDtos.GrantRequest request) {
        GrantPreview preview = grants.preview(new PermissionCommands.Grant(
                parse(SubjectType.class, request.subjectType(), "subjectType"),
                request.subjectRef(),
                parse(ScopeType.class, request.scopeType(), "scopeType"),
                request.scopeGroupId(),
                request.scopeSiteId(),
                request.domainPattern(),
                parse(PermissionLevel.class, request.level(), "level"),
                request.expiresAt()));

        return new PermissionDtos.PreviewResponse(
                preview.matched().stream()
                        .map(site -> new PermissionDtos.MatchedSiteResponse(
                                site.id(), site.domain(), site.name(), site.alreadyReachable()))
                        .toList(),
                preview.totalMatched(),
                preview.truncated(),
                preview.newlyVisibleToSubject(),
                preview.coversFutureDomains());
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Revoke a permission grant")
    public ResponseEntity<Void> revoke(@PathVariable UUID id) {
        grants.revoke(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * What the current caller may do to one site.
     *
     * <p>Intended for the console, so it can grey out actions rather than offer them and then
     * fail. It is a convenience, never the check: every endpoint re-evaluates server-side.
     */
    @GetMapping("/effective")
    @Operation(summary = "Your effective permission on a site")
    public PermissionDtos.EffectivePermissionResponse effective(@RequestParam UUID siteId) {
        ProxySite site = sites.get(siteId);
        PermissionLevel level = permissions.effectiveLevel(site);

        return new PermissionDtos.EffectivePermissionResponse(
                site.id(),
                site.domain().value(),
                level == null ? null : level.name(),
                satisfies(level, PermissionLevel.READ),
                satisfies(level, PermissionLevel.OPERATE),
                satisfies(level, PermissionLevel.MANAGE),
                satisfies(level, PermissionLevel.ADMIN));
    }

    private static boolean satisfies(PermissionLevel level, PermissionLevel required) {
        return level != null && level.satisfies(required);
    }

    private PermissionDtos.Response toResponse(PermissionGrant grant) {
        return new PermissionDtos.Response(
                grant.id(),
                grant.subjectType().name(),
                grant.subjectRef(),
                grant.scopeType().name(),
                grant.scopeGroupId(),
                grant.scopeSiteId(),
                grant.domainPattern() == null ? null : grant.domainPattern().value(),
                grant.level().name(),
                grant.grantedBy(),
                grant.grantedAt(),
                grant.expiresAt(),
                !grant.isActiveAt(clock.instant()));
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, String field) {
        if (value == null) {
            throw new ValidationException(field, field + " is required");
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ValidationException(field, "'" + value + "' is not valid. Expected one of: "
                    + String.join(", ", Arrays.stream(type.getEnumConstants()).map(Enum::name).toList()));
        }
    }
}
