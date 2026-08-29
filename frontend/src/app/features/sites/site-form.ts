import { Component, OnInit, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { CertificatesApi, InstancesApi, SitesApi } from '../../core/api/resources';
import { Certificate, HeaderRule, NginxInstance, Site, SiteRequest, Upstream } from '../../core/api/models';
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
    };

    this.saving.set(true);
    const existing = this.existing();
    const call = existing
      ? this.api.update(existing.id, request, existing.version)
      : this.api.create(request);

    call.subscribe({
      next: (site) => {
        this.saving.set(false);
        this.notifications.success(existing ? 'Site updated' : 'Site created', site.domain);
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
