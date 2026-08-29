import { Component, OnInit, computed, inject, input, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { CertificatesApi, DeploymentsApi, PermissionsApi, SitesApi } from '../../core/api/resources';
import { Certificate, Deployment, EffectivePermission, Preview, Site, UpstreamCheck } from '../../core/api/models';
import { Notifications } from '../../shared/notifications';
import { PageHeader } from '../../shared/page';
import { StatusPill } from '../../shared/status-pill';
import { BytesPipe, DateTimePipe, RelativePipe } from '../../shared/formatting';

@Component({
  selector: 'app-site-detail',
  standalone: true,
  imports: [RouterLink, FormsModule, PageHeader, StatusPill, RelativePipe, DateTimePipe, BytesPipe],
  templateUrl: './site-detail.html',
  styleUrl: './site-detail.css',
})
export class SiteDetail implements OnInit {
  /** Bound from the route by withComponentInputBinding. */
  readonly id = input.required<string>();

  private readonly api = inject(SitesApi);
  private readonly certificatesApi = inject(CertificatesApi);
  private readonly deploymentsApi = inject(DeploymentsApi);
  private readonly permissionsApi = inject(PermissionsApi);
  private readonly notifications = inject(Notifications);
  private readonly router = inject(Router);

  readonly site = signal<Site | null>(null);
  readonly certificate = signal<Certificate | null>(null);
  readonly deployments = signal<Deployment[]>([]);
  readonly preview = signal<Preview | null>(null);
  readonly previewOpen = signal(false);
  readonly upstreamChecks = signal<UpstreamCheck[] | null>(null);
  readonly checkingUpstreams = signal(false);
  readonly loading = signal(true);

  /**
   * What this user may do to this site, answered by the server.
   *
   * Used only to decide what to show. Every action is re-authorised on the request itself, so a
   * stale or spoofed value here changes what is offered, never what is permitted.
   */
  readonly permission = signal<EffectivePermission | null>(null);

  readonly canOperate = computed(() => this.permission()?.canOperate ?? false);
  readonly canManage = computed(() => this.permission()?.canManage ?? false);

  readonly renewUntil = signal('');

  ngOnInit(): void {
    this.load();
  }

  private load(): void {
    this.loading.set(true);
    this.api.get(this.id()).subscribe({
      next: (site) => {
        this.site.set(site);
        this.loading.set(false);
        if (site.sslCertificateId) {
          this.certificatesApi.get(site.sslCertificateId).subscribe({
            next: (certificate) => this.certificate.set(certificate),
            error: () => this.certificate.set(null),
          });
        }
        this.deploymentsApi.list(site.nginxInstanceId, 0, 8).subscribe({
          next: (page) => this.deployments.set(page.content),
          error: () => this.deployments.set([]),
        });
      },
      error: (problem) => {
        this.notifications.problem(problem);
        this.loading.set(false);
      },
    });

    this.permissionsApi.effective(this.id()).subscribe({
      next: (permission) => this.permission.set(permission),
      error: () => this.permission.set(null),
    });
  }

  toggle(): void {
    const site = this.site();
    if (!site) {
      return;
    }
    const request = site.enabled ? this.api.disable(site.id) : this.api.enable(site.id);
    request.subscribe({
      next: (updated) => {
        this.site.set(updated);
        this.notifications.success(`Site ${updated.enabled ? 'enabled' : 'disabled'}`);
      },
      error: (problem) => this.notifications.problem(problem),
    });
  }

  showPreview(): void {
    this.previewOpen.set(true);
    this.api.preview(this.id()).subscribe({
      next: (preview) => this.preview.set(preview),
      error: (problem) => {
        this.notifications.problem(problem);
        this.previewOpen.set(false);
      },
    });
  }

  /**
   * Asks the host whether it can reach this site's upstreams.
   *
   * On demand rather than on load: it opens a real connection from the NGINX host to each
   * upstream, which is not something a page should do every time someone glances at it.
   */
  checkUpstreams(): void {
    this.checkingUpstreams.set(true);
    this.api.checkUpstreams(this.id()).subscribe({
      next: (results) => {
        this.upstreamChecks.set(results);
        this.checkingUpstreams.set(false);
      },
      error: (problem) => {
        this.notifications.problem(problem);
        this.checkingUpstreams.set(false);
      },
    });
  }

  deploy(): void {
    this.api.deploy(this.id()).subscribe({
      next: () => {
        this.notifications.info(
          'Deployment queued',
          'The bundle is rendered from current state and applied in the background.',
        );
        // Give the dispatcher a moment, then show what happened rather than leaving a stale list.
        setTimeout(() => this.load(), 2500);
      },
      error: (problem) => this.notifications.problem(problem),
    });
  }

  renew(): void {
    const site = this.site();
    const value = this.renewUntil();
    if (!site || !value) {
      return;
    }
    this.api.renew(site.id, new Date(value).toISOString(), site.version).subscribe({
      next: (updated) => {
        this.site.set(updated);
        this.renewUntil.set('');
        this.notifications.success('Expiry extended', `Now expires ${new Date(updated.expiresAt!).toLocaleString()}`);
      },
      error: (problem) => this.notifications.problem(problem),
    });
  }

  removeExpiry(): void {
    this.api.removeExpiry(this.id()).subscribe({
      next: (updated) => {
        this.site.set(updated);
        this.notifications.success('Expiry removed', 'This site no longer expires.');
      },
      error: (problem) => this.notifications.problem(problem),
    });
  }

  remove(): void {
    const site = this.site();
    if (!site || !confirm(`Delete ${site.domain}? Its configuration leaves the host on the next deployment.`)) {
      return;
    }
    this.api.remove(site.id, site.version).subscribe({
      next: () => {
        this.notifications.success('Site deleted');
        this.router.navigate(['/sites']);
      },
      error: (problem) => this.notifications.problem(problem),
    });
  }
}
