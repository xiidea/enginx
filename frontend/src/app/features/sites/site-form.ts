import { Component, OnInit, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { CertificatesApi, InstancesApi, SitesApi } from '../../core/api/resources';
import {
  Certificate,
  HeaderRule,
  LocationRule,
  NginxInstance,
  ProtectionMode,
  Site,
  SiteRequest,
  Upstream,
} from '../../core/api/models';
import { Notifications } from '../../shared/notifications';
import { PageHeader } from '../../shared/page';

@Component({
  selector: 'app-site-form',
  standalone: true,
  imports: [FormsModule, RouterLink, PageHeader],
  templateUrl: './site-form.html',
  styleUrl: './site-form.css',
})
export class SiteForm implements OnInit {
  /** Present when editing; absent when creating. */
  readonly id = input<string | undefined>(undefined);

  private readonly api = inject(SitesApi);
  private readonly instancesApi = inject(InstancesApi);
  private readonly certificatesApi = inject(CertificatesApi);
  private readonly notifications = inject(Notifications);
  private readonly router = inject(Router);

  readonly editing = computed(() => !!this.id());
  readonly saving = signal(false);
  readonly instances = signal<NginxInstance[]>([]);
  readonly certificates = signal<Certificate[]>([]);
  private readonly existing = signal<Site | null>(null);

  readonly name = signal('');
  readonly domain = signal('');
  readonly nginxInstanceId = signal('');
  readonly activeFrom = signal('');
  readonly expiresAt = signal('');
  readonly enabled = signal(true);
  readonly sslEnabled = signal(false);
  readonly forceHttps = signal(false);
  readonly hstsEnabled = signal(false);
  readonly websocketEnabled = signal(false);
  readonly sslCertificateId = signal('');
  readonly loadBalancingMethod = signal('ROUND_ROBIN');
  readonly connectTimeoutSeconds = signal(60);
  readonly readTimeoutSeconds = signal(60);
  readonly sendTimeoutSeconds = signal(60);
  readonly maxBodySizeMb = signal(1);
  readonly upstreams = signal<Upstream[]>([{ scheme: 'http', host: '', port: 8080 }]);
  readonly headers = signal<HeaderRule[]>([]);
  /**
   * The site's paths besides the root, in order. The root, `/` by prefix, is never edited here: it
   * is always sent first whenever there are others, so every request matches a location. Without
   * it NGINX answers paths no location matches from its own document root, not the upstream.
   */
  readonly locations = signal<LocationRule[]>([]);
  readonly protectionMode = signal<ProtectionMode>('NONE');
  readonly basicUsers = signal<BasicUserRow[]>([]);
  /** Basic authentication on the gate's login page rather than the browser's prompt. */
  readonly basicLoginPage = signal(false);
  /** One address or *@domain per line. */
  readonly allowedEmails = signal('');
  readonly sessionHours = signal(24);
  readonly oidcAnyUser = signal(true);
  /** One email or username per line. */
  readonly oidcUsers = signal('');
  /** One per line: `role` for a realm role, `client:role` for a client role. */
  readonly oidcRoles = signal('');

  /** Why each unavailable mode cannot be chosen here; absent for those that can. */
  readonly unavailableModes = signal<Partial<Record<ProtectionMode, string>>>({});
  /** The mode the site was loaded with, which it may keep even if the platform no longer offers it. */
  private readonly savedMode = signal<ProtectionMode>('NONE');

  modeDisabled(mode: ProtectionMode): boolean {
    return !!this.unavailableModes()[mode] && this.savedMode() !== mode;
  }

  /** Mirrors the server's rule: credentials are only ever asked for over HTTPS. */
  readonly canProtect = computed(() => this.sslEnabled() && this.forceHttps());

  /**
   * Certificates that actually cover the domain being configured.
   *
   * Offering one that does not would produce a site NGINX loads and every browser rejects, so the
   * mismatch is caught while choosing rather than at the next deployment.
   */
  readonly usableCertificates = computed(() => {
    const domain = this.domain().trim().toLowerCase();
    return this.certificates().filter(
      (certificate) =>
        certificate.status !== 'REVOKED' &&
        certificate.status !== 'EXPIRED' &&
        (!domain || certificate.domains.some((covered) => matches(covered, domain))),
    );
  });

  ngOnInit(): void {
    this.instancesApi.list().subscribe({
      next: (instances) => {
        this.instances.set(instances);
        if (!this.nginxInstanceId() && instances.length === 1) {
          this.nginxInstanceId.set(instances[0].id);
        }
      },
      error: (problem) => this.notifications.problem(problem),
    });

    this.api.protectionModes().subscribe({
      next: (modes) =>
        this.unavailableModes.set(
          Object.fromEntries(modes.filter((m) => !m.available).map((m) => [m.mode, m.reason ?? 'not configured'])),
        ),
      // Without the list every mode stays selectable; the server still refuses what it cannot serve.
      error: () => this.unavailableModes.set({}),
    });

    this.certificatesApi.list().subscribe({
      next: (certificates) => this.certificates.set(certificates),
      error: () => this.certificates.set([]),
    });

    const id = this.id();
    if (id) {
      this.api.get(id).subscribe({
        next: (site) => this.fill(site),
        error: (problem) => this.notifications.problem(problem),
      });
    }
  }

  private fill(site: Site): void {
    this.existing.set(site);
    this.name.set(site.name);
    this.domain.set(site.domain);
    this.nginxInstanceId.set(site.nginxInstanceId);
    this.activeFrom.set(toLocalInput(site.activeFrom));
    this.expiresAt.set(toLocalInput(site.expiresAt));
    this.enabled.set(site.enabled);
    this.sslEnabled.set(site.sslEnabled);
    this.forceHttps.set(site.forceHttps);
    this.hstsEnabled.set(site.hstsEnabled);
    this.websocketEnabled.set(site.websocketEnabled);
    this.sslCertificateId.set(site.sslCertificateId ?? '');
    this.loadBalancingMethod.set(site.loadBalancingMethod);
    this.connectTimeoutSeconds.set(site.connectTimeoutSeconds);
    this.readTimeoutSeconds.set(site.readTimeoutSeconds);
    this.sendTimeoutSeconds.set(site.sendTimeoutSeconds);
    this.maxBodySizeMb.set(Math.max(1, Math.round(site.maxBodySizeBytes / 1048576)));
    this.upstreams.set(site.upstreams.length ? [...site.upstreams] : [{ scheme: 'http', host: '', port: 8080 }]);
    this.headers.set([...site.headers]);
    // A site with only the root location has none of its own to show.
    this.locations.set(site.locations.filter((l) => !isRoot(l)));
    this.protectionMode.set(site.protection.mode as ProtectionMode);
    this.savedMode.set(site.protection.mode as ProtectionMode);
    // The server never returns a password or its hash, so an existing user starts with an empty
    // password field, which saving sends as "keep the current one".
    this.basicUsers.set(site.protection.basicUsers.map((user) => ({ username: user.username, password: '', existing: true })));
    if (site.protection.basicLoginPage) {
      this.basicLoginPage.set(true);
      this.sessionHours.set(Math.max(1, Math.round((site.protection.basicLoginPage.sessionTtlSeconds ?? 86400) / 3600)));
    }
    if (site.protection.emailOtp) {
      this.allowedEmails.set(site.protection.emailOtp.allowedEmails.join('\n'));
      this.sessionHours.set(Math.max(1, Math.round(site.protection.emailOtp.sessionTtlSeconds / 3600)));
    }
    if (site.protection.oidc) {
      const oidc = site.protection.oidc;
      this.oidcAnyUser.set(oidc.anyUser);
      this.oidcUsers.set(oidc.users.join('\n'));
      this.oidcRoles.set(oidc.roles.map((role) => (role.client ? `${role.client}:${role.role}` : role.role)).join('\n'));
      this.sessionHours.set(Math.max(1, Math.round(oidc.sessionTtlSeconds / 3600)));
    }
  }

  addUpstream(): void {
    this.upstreams.update((all) => [...all, { scheme: 'http', host: '', port: 8080 }]);
  }

  removeUpstream(index: number): void {
    this.upstreams.update((all) => all.filter((_, i) => i !== index));
  }

  updateUpstream(index: number, patch: Partial<Upstream>): void {
    this.upstreams.update((all) => all.map((upstream, i) => (i === index ? { ...upstream, ...patch } : upstream)));
  }

  addHeader(): void {
    this.headers.update((all) => [...all, { direction: 'REQUEST', name: '', value: '' }]);
  }

  removeHeader(index: number): void {
    this.headers.update((all) => all.filter((_, i) => i !== index));
  }

  updateHeader(index: number, patch: Partial<HeaderRule>): void {
    this.headers.update((all) => all.map((header, i) => (i === index ? { ...header, ...patch } : header)));
  }

  onProtectionModeChange(mode: ProtectionMode): void {
    this.protectionMode.set(mode);
    if (mode === 'BASIC' && !this.basicUsers().length) {
      this.addBasicUser();
    }
  }

  addBasicUser(): void {
    this.basicUsers.update((all) => [...all, { username: '', password: '', existing: false }]);
  }

  removeBasicUser(index: number): void {
    this.basicUsers.update((all) => all.filter((_, i) => i !== index));
  }

  updateBasicUser(index: number, patch: Partial<BasicUserRow>): void {
    this.basicUsers.update((all) => all.map((user, i) => (i === index ? { ...user, ...patch } : user)));
  }

  addLocation(): void {
    this.locations.update((all) => [...all, { pathPattern: '', matchType: 'PREFIX' }]);
  }

  removeLocation(index: number): void {
    this.locations.update((all) => all.filter((_, i) => i !== index));
  }

  updateLocation(index: number, patch: Partial<LocationRule>): void {
    this.locations.update((all) => all.map((location, i) => (i === index ? { ...location, ...patch } : location)));
  }

  /** Mirrors the server's rule so the impossible combination cannot be submitted. */
  onSslChange(enabled: boolean): void {
    this.sslEnabled.set(enabled);
    if (!enabled) {
      this.forceHttps.set(false);
      this.hstsEnabled.set(false);
      this.sslCertificateId.set('');
    }
  }

  save(): void {
    const request: SiteRequest = {
      name: this.name().trim(),
      domain: this.domain().trim(),
      nginxInstanceId: this.nginxInstanceId(),
      // undefined rather than null: the field is then omitted from the JSON entirely, which is
      // what the API contract describes. The server reads both as "no bound", but sending what the
      // schema says keeps the request valid against the published spec.
      activeFrom: this.activeFrom() ? new Date(this.activeFrom()).toISOString() : undefined,
      expiresAt: this.expiresAt() ? new Date(this.expiresAt()).toISOString() : undefined,
      enabled: this.enabled(),
      sslEnabled: this.sslEnabled(),
      forceHttps: this.forceHttps(),
      hstsEnabled: this.hstsEnabled(),
      websocketEnabled: this.websocketEnabled(),
      // Omitted rather than explicitly null, which is what the published schema describes for an
      // absent optional field.
      sslCertificateId: this.sslCertificateId() || undefined,
      loadBalancingMethod: this.loadBalancingMethod(),
      connectTimeoutSeconds: this.connectTimeoutSeconds(),
      readTimeoutSeconds: this.readTimeoutSeconds(),
      sendTimeoutSeconds: this.sendTimeoutSeconds(),
      maxBodySizeBytes: this.maxBodySizeMb() * 1048576,
      upstreams: this.upstreams().filter((upstream) => upstream.host.trim()),
      headers: this.headers().filter((header) => header.name.trim()),
      locations: locationsToSave(this.locations()),
      protection: {
        mode: this.protectionMode(),
        basicUsers:
          this.protectionMode() === 'BASIC'
            ? this.basicUsers()
                .filter((user) => user.username.trim())
                // An empty password is omitted, which the server reads as "keep the current one".
                .map((user) => ({ username: user.username.trim(), password: user.password || undefined }))
            : [],
        basicLoginPage:
          this.protectionMode() === 'BASIC' && this.basicLoginPage()
            ? { sessionTtlSeconds: this.sessionHours() * 3600 }
            : undefined,
        emailOtp:
          this.protectionMode() === 'EMAIL_OTP'
            ? {
                allowedEmails: this.allowedEmails()
                  .split(/[\s,]+/)
                  .map((email) => email.trim())
                  .filter((email) => email),
                sessionTtlSeconds: this.sessionHours() * 3600,
              }
            : undefined,
        oidc:
          this.protectionMode() === 'OIDC'
            ? {
                anyUser: this.oidcAnyUser(),
                users: this.oidcAnyUser() ? [] : lines(this.oidcUsers()),
                roles: this.oidcAnyUser()
                  ? []
                  : lines(this.oidcRoles()).map((entry) => {
                      // Role names cannot contain a colon, so the first one separates the client.
                      const colon = entry.indexOf(':');
                      return colon < 0
                        ? { role: entry }
                        : { client: entry.slice(0, colon), role: entry.slice(colon + 1) };
                    }),
                sessionTtlSeconds: this.sessionHours() * 3600,
              }
            : undefined,
      },
    };

    this.saving.set(true);
    const existing = this.existing();
    const call = existing
      ? this.api.update(existing.id, request, existing.version)
      : this.api.create(request);

    call.subscribe({
      next: (site) => {
        this.saving.set(false);
        // Saving never deploys, which is easy to forget when the change is who may get in: the
        // host keeps the old rules until the next deployment.
        this.notifications.success(
          existing ? 'Site updated' : 'Site created',
          `${site.domain}: deploy it to apply the change on its host.`,
        );
        this.router.navigate(['/sites', site.id]);
      },
      error: (problem) => {
        this.saving.set(false);
        // The server's field errors are shown as they arrived; it validates far more than this
        // form can, and paraphrasing would lose the specifics.
        this.notifications.problem(problem);
      },
    });
  }
}

/** One basic authentication user as edited; existing users have a password the form never sees. */
interface BasicUserRow {
  username: string;
  password: string;
  existing: boolean;
}

/**
 * What a site's locations are saved as. Always sent, so what the form shows is what is saved:
 * nothing for the root alone, otherwise the root first and then the paths entered. A row left
 * empty is dropped, and so is a root entered by hand, which is already there.
 */
export function locationsToSave(rows: LocationRule[]): LocationRule[] {
  const extra = rows
    .map((location) => ({ pathPattern: location.pathPattern.trim(), matchType: location.matchType }))
    .filter((location) => location.pathPattern && !isRoot(location));
  return extra.length ? [{ pathPattern: '/', matchType: 'PREFIX' }, ...extra] : [];
}

/** The root location: every path, by prefix. */
export function isRoot(location: LocationRule): boolean {
  return location.pathPattern.trim() === '/' && location.matchType === 'PREFIX';
}

/** Non-empty trimmed lines. */
function lines(text: string): string[] {
  return text
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line);
}

function toLocalInput(iso: string | null): string {
  if (!iso) {
    return '';
  }
  const date = new Date(iso);
  const offset = date.getTimezoneOffset() * 60000;
  return new Date(date.getTime() - offset).toISOString().slice(0, 16);
}

/** Exact match, or a single leading wildcard label. */
function matches(covered: string, domain: string): boolean {
  if (covered === domain) {
    return true;
  }
  if (!covered.startsWith('*.')) {
    return false;
  }
  const suffix = covered.slice(1);
  return domain.endsWith(suffix) && !domain.slice(0, domain.length - suffix.length).includes('.');
}
