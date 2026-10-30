package net.xiidea.enginx.api.proxy;

import net.xiidea.enginx.api.common.EntityVersion;
import net.xiidea.enginx.api.common.PageResponse;
import net.xiidea.enginx.api.proxy.dto.CloneRequest;
import net.xiidea.enginx.api.proxy.dto.ProxySiteRequest;
import net.xiidea.enginx.api.proxy.dto.ProxySiteResponse;
import net.xiidea.enginx.api.proxy.dto.ProxySiteSummaryResponse;
import net.xiidea.enginx.api.proxy.dto.RenewRequest;
import net.xiidea.enginx.application.proxy.ProxySiteChanges;
import net.xiidea.enginx.application.proxy.ProxySiteCommands;
import net.xiidea.enginx.application.notification.SiteNotificationService;
import net.xiidea.enginx.application.proxy.ProxySiteService;
import net.xiidea.enginx.domain.deployment.Deployment;
import net.xiidea.enginx.domain.proxy.ProxySite;
import net.xiidea.enginx.domain.proxy.ProxySiteQuery;
import net.xiidea.enginx.domain.proxy.ProxySiteSortField;
import net.xiidea.enginx.domain.proxy.SiteStatus;
import net.xiidea.enginx.domain.shared.DomainName;
import net.xiidea.enginx.domain.shared.SortDirection;
import net.xiidea.enginx.domain.shared.ValidationException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/proxy-sites")
@Tag(name = "Proxy sites", description = "Reverse-proxy site management")
public class ProxySiteController {

    /** The value of ?deploy= that asks for a change to be deployed when it changes what the host serves. */
    static final String DEPLOY_AUTO = "auto";

    private final ProxySiteService service;
    private final ProxySiteChanges changes;
    private final ProxySiteDtoMapper mapper;
    private final SiteNotificationService notifications;

    public ProxySiteController(ProxySiteService service, ProxySiteChanges changes, ProxySiteDtoMapper mapper,
                               SiteNotificationService notifications) {
        this.service = service;
        this.changes = changes;
        this.mapper = mapper;
        this.notifications = notifications;
    }

    @GetMapping("/{id}/notifications")
    @Operation(summary = "Who is told about this site",
            description = "A site nobody has configured returns the defaults: expiry warnings on, "
                    + "and only the platform's operator addresses receive them.")
    public NotificationSettingsResponse notifications(@PathVariable UUID id) {
        return NotificationSettingsResponse.of(notifications.get(id));
    }

    @PutMapping("/{id}/notifications")
    @Operation(summary = "Choose who is told about this site",
            description = "Replaces the settings wholesale, because the console sends back the "
                    + "list it displayed — two people editing at once cannot then produce a union "
                    + "neither of them chose. Requires OPERATE: this is operational, and does not "
                    + "change what anybody is served. Never triggers a deployment.")
    public NotificationSettingsResponse configureNotifications(
            @PathVariable UUID id, @Valid @RequestBody NotificationSettingsRequest request) {

        return NotificationSettingsResponse.of(notifications.configure(id,
                request.expiryEnabledOrDefault(),
                request.subscribers() == null ? Set.of() : Set.copyOf(request.subscribers())));
    }

    /**
     * @param subscribers addresses told in addition to the platform's operator list, never instead
     * @param updatedBy   null while the site still has the defaults, which is the common case
     */
    @Schema(name = "SiteNotificationSettingsResponse",
            requiredProperties = {"expiryEnabled", "subscribers"})
    public record NotificationSettingsResponse(boolean expiryEnabled, List<String> subscribers,
                                                String updatedBy, Instant updatedAt) {

        static NotificationSettingsResponse of(
                net.xiidea.enginx.domain.notification.SiteNotificationSettings settings) {
            return new NotificationSettingsResponse(settings.expiryEnabled(),
                    settings.subscribers().stream().sorted().toList(),
                    settings.updatedBy(), settings.updatedAt());
        }
    }

    @Schema(name = "SiteNotificationSettingsRequest")
    public record NotificationSettingsRequest(Boolean expiryEnabled, List<String> subscribers) {

        /**
         * Boxed and defaulted, because a primitive component makes Jackson reject a body that
         * omits it — turning an optional field into a required one the schema does not declare.
         * Absent means on, which is what a site does before anybody configures it.
         */
        boolean expiryEnabledOrDefault() {
            return expiryEnabled == null || expiryEnabled;
        }
    }

    @GetMapping
    @Operation(summary = "List proxy sites",
            description = "Filtering, sorting and pagination are applied in the query, never over an already-fetched "
                    + "page. Sort as sort=domain,asc; repeat the parameter for multiple sorts.")
    public PageResponse<ProxySiteSummaryResponse> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) List<String> status,
            @RequestParam(required = false) UUID nginxInstanceId,
            @RequestParam(required = false) Boolean sslEnabled,
            @RequestParam(required = false) Instant expiringBefore,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            HttpServletRequest request) {

        // Read `sort` from the raw request rather than binding it as List<String>: Spring splits
        // a bound list on commas, which would turn the conventional `sort=domain,asc` into two
        // separate values. Repeating the parameter for multiple sorts still works.
        String[] sort = request.getParameterValues("sort");

        ProxySiteQuery query = new ProxySiteQuery(
                search, parseStatuses(status), nginxInstanceId, sslEnabled, expiringBefore,
                page, size, parseSorts(sort));

        return PageResponse.from(service.search(query), mapper::toSummary);
    }

    @io.swagger.v3.oas.annotations.media.Schema(name = "ProtectionModeAvailability",
            requiredProperties = {"mode", "available"})
    public record ProtectionModeResponse(String mode, boolean available, String reason) {
    }

    @GetMapping("/protection-modes")
    @Operation(summary = "Which protection modes this platform can serve",
            description = "A gate mode is unavailable until its platform settings are configured; saving a site "
                    + "with one is refused. The reason says which setting is missing.")
    public List<ProtectionModeResponse> protectionModes() {
        return service.protectionModes().stream()
                .map(m -> new ProtectionModeResponse(m.mode().name(), m.available(), m.reason()))
                .toList();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch one proxy site")
    public ResponseEntity<ProxySiteResponse> get(@PathVariable UUID id) {
        return ok(service.get(id));
    }

    @PostMapping
    @Operation(summary = "Create a proxy site")
    public ResponseEntity<ProxySiteResponse> create(@Valid @RequestBody ProxySiteRequest request,
                                                    @Parameter(description = DEPLOY_DESCRIPTION)
                                                    @RequestParam(required = false) String deploy,
                                                    UriComponentsBuilder uriBuilder) {
        ProxySiteCommands.Create command = new ProxySiteCommands.Create(
                mapper.toSpec(request), mapper.toAdminState(request.enabled()),
                mapper.toProtectionInput(request.protection()));
        ProxySiteChanges.Result result = auto(deploy)
                ? changes.create(command)
                : new ProxySiteChanges.Result(service.create(command), null);

        URI location = uriBuilder.path("/api/v1/proxy-sites/{id}").buildAndExpand(result.site().id()).toUri();
        return ResponseEntity.created(location)
                .eTag(EntityVersion.toETag(result.site().version()))
                .body(body(result));
    }

    static final String DEPLOY_DESCRIPTION = "auto: deploy in the same request when the change alters what the "
            + "host serves (a name-only edit does not). Omitted, nothing is deployed, as before.";

    @PutMapping("/{id}")
    @Operation(summary = "Replace a proxy site",
            description = "Send the version last read as If-Match to have concurrent edits rejected. "
                    + "Locations and protection left out of the request are kept as they are.")
    public ResponseEntity<ProxySiteResponse> update(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody ProxySiteRequest request,
            @Parameter(description = DEPLOY_DESCRIPTION) @RequestParam(required = false) String deploy) {

        ProxySiteCommands.Update command = new ProxySiteCommands.Update(
                id, mapper.toSpec(request), EntityVersion.parseIfMatch(ifMatch),
                mapper.toProtectionInput(request.protection()),
                request.locations() == null);
        return ok(auto(deploy) ? changes.update(command) : new ProxySiteChanges.Result(service.update(command), null));
    }

    /**
     * What deleting a site with {@code deploy=auto} set in motion. Without it the reply is 204 with
     * no body, as it always was.
     *
     * @param deploymentId the deployment taking the site off its host; absent when the host was
     *                     not serving it, or one already under way will be followed by the next
     */
    @Schema(name = "SiteDeletionResponse")
    public record DeletionResponse(UUID deploymentId) {
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a proxy site",
            description = "204 with no body; with deploy=auto, 200 and the deployment taking the site off its host.",
            responses = {
                    @ApiResponse(responseCode = "204", description = "Deleted; nothing deployed"),
                    @ApiResponse(responseCode = "200", description = "Deleted with deploy=auto",
                            content = @Content(mediaType = "application/json",
                                    schema = @Schema(implementation = DeletionResponse.class)))})
    public ResponseEntity<DeletionResponse> delete(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Parameter(description = "auto: deploy in the same request when the host was serving the site, "
                    + "so it stops at once. Omitted, the site leaves the host with the next deployment, as before.")
            @RequestParam(required = false) String deploy) {

        Long version = EntityVersion.parseIfMatch(ifMatch);
        if (!auto(deploy)) {
            service.delete(id, version);
            return ResponseEntity.noContent().build();
        }
        Deployment deployment = changes.delete(id, version);
        return ResponseEntity.ok(new DeletionResponse(deployment == null ? null : deployment.id()));
    }

    @PostMapping("/{id}/enable")
    @Operation(summary = "Enable a proxy site")
    public ResponseEntity<ProxySiteResponse> enable(@PathVariable UUID id,
            @Parameter(description = DEPLOY_DESCRIPTION) @RequestParam(required = false) String deploy) {
        return ok(auto(deploy) ? changes.enable(id) : new ProxySiteChanges.Result(service.enable(id), null));
    }

    @PostMapping("/{id}/sign-out-everyone")
    @Operation(summary = "End every gate sign-in session",
            description = "Replaces the site's gate key. Sessions signed with the old key stop working once the "
                    + "site is next deployed; with deploy=auto, that is now.")
    public ResponseEntity<ProxySiteResponse> signOutEveryone(@PathVariable UUID id,
            @Parameter(description = DEPLOY_DESCRIPTION) @RequestParam(required = false) String deploy) {
        return ok(auto(deploy) ? changes.signOutEveryone(id)
                : new ProxySiteChanges.Result(service.signOutEveryone(id), null));
    }

    @PostMapping("/{id}/disable")
    @Operation(summary = "Disable a proxy site")
    public ResponseEntity<ProxySiteResponse> disable(@PathVariable UUID id,
            @Parameter(description = DEPLOY_DESCRIPTION) @RequestParam(required = false) String deploy) {
        return ok(auto(deploy) ? changes.disable(id) : new ProxySiteChanges.Result(service.disable(id), null));
    }

    @PostMapping("/{id}/renew")
    @Operation(summary = "Extend or set the expiry")
    public ResponseEntity<ProxySiteResponse> renew(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody RenewRequest request,
            @Parameter(description = DEPLOY_DESCRIPTION) @RequestParam(required = false) String deploy) {

        ProxySiteCommands.Renew command = new ProxySiteCommands.Renew(
                id, request.expiresAt(), EntityVersion.parseIfMatch(ifMatch));
        return ok(auto(deploy) ? changes.renew(command) : new ProxySiteChanges.Result(service.renew(command), null));
    }

    @DeleteMapping("/{id}/expiration")
    @Operation(summary = "Remove the expiry so the site never expires")
    public ResponseEntity<ProxySiteResponse> removeExpiry(@PathVariable UUID id,
            @Parameter(description = DEPLOY_DESCRIPTION) @RequestParam(required = false) String deploy) {
        return ok(auto(deploy) ? changes.removeExpiry(id)
                : new ProxySiteChanges.Result(service.removeExpiry(id), null));
    }

    @PostMapping("/{id}/clone")
    @Operation(summary = "Clone a proxy site",
            description = "The copy is created disabled so a half-edited duplicate cannot take traffic.")
    public ResponseEntity<ProxySiteResponse> clone(@PathVariable UUID id,
                                                    @Valid @RequestBody CloneRequest request,
                                                    UriComponentsBuilder uriBuilder) {
        ProxySite clone = service.clone(new ProxySiteCommands.Clone(
                id, request.name(), DomainName.of(request.domain())));

        URI location = uriBuilder.path("/api/v1/proxy-sites/{id}").buildAndExpand(clone.id()).toUri();
        return ResponseEntity.created(location)
                .eTag(EntityVersion.toETag(clone.version()))
                .body(mapper.toResponse(clone));
    }

    private static boolean auto(String deploy) {
        if (deploy == null || deploy.isBlank()) {
            return false;
        }
        if (!DEPLOY_AUTO.equalsIgnoreCase(deploy.trim())) {
            throw new ValidationException("deploy",
                    "'" + deploy + "' is not valid. Expected: auto, or leave it out");
        }
        return true;
    }

    private ProxySiteResponse body(ProxySiteChanges.Result result) {
        ProxySiteResponse response = mapper.toResponse(result.site());
        return result.deployment() == null ? response : response.withDeployment(result.deployment().id());
    }

    private ResponseEntity<ProxySiteResponse> ok(ProxySiteChanges.Result result) {
        return ResponseEntity.ok()
                .eTag(EntityVersion.toETag(result.site().version()))
                .body(body(result));
    }

    private ResponseEntity<ProxySiteResponse> ok(ProxySite site) {
        return ResponseEntity.ok()
                .eTag(EntityVersion.toETag(site.version()))
                .body(mapper.toResponse(site));
    }

    private static Set<SiteStatus> parseStatuses(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Set.of();
        }
        Set<SiteStatus> statuses = new LinkedHashSet<>();
        for (String value : raw) {
            try {
                statuses.add(SiteStatus.valueOf(value.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new ValidationException("status", "'" + value + "' is not a valid status. Expected one of: "
                        + names(SiteStatus.values()));
            }
        }
        return statuses;
    }

    /**
     * Accepts {@code sort=domain,asc}. The field is resolved to an enum constant, so an
     * unrecognised value is rejected here and can never reach the persistence layer as a
     * property path.
     */
    private static List<ProxySiteQuery.Sort> parseSorts(String[] raw) {
        if (raw == null || raw.length == 0) {
            return List.of();
        }
        List<ProxySiteQuery.Sort> sorts = new ArrayList<>();
        for (String value : raw) {
            String[] parts = value.split(",");
            ProxySiteSortField field;
            try {
                field = ProxySiteSortField.valueOf(toScreamingSnake(parts[0].trim()));
            } catch (IllegalArgumentException e) {
                throw new ValidationException("sort", "'" + parts[0] + "' is not a sortable field. Expected one of: "
                        + names(ProxySiteSortField.values()));
            }
            SortDirection direction = parts.length > 1 && parts[1].trim().equalsIgnoreCase("desc")
                    ? SortDirection.DESC
                    : SortDirection.ASC;
            sorts.add(new ProxySiteQuery.Sort(field, direction));
        }
        return sorts;
    }

    /** Accepts both {@code createdAt} and {@code created_at}. */
    private static String toScreamingSnake(String value) {
        return value.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
    }

    private static String names(Enum<?>[] values) {
        return String.join(", ", Arrays.stream(values).map(Enum::name).toList());
    }
}
