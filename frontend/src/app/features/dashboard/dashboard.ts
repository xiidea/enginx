import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { CertificatesApi, DeploymentsApi, InstancesApi, SitesApi } from '../../core/api/resources';
import { Certificate, Deployment, NginxInstance, SiteSummary } from '../../core/api/models';
import { Notifications } from '../../shared/notifications';
import { PageHeader } from '../../shared/page';
import { StatusPill } from '../../shared/status-pill';
import { DateTimePipe, RelativePipe } from '../../shared/formatting';

/**
 * What needs attention, before what merely exists.
 *
 * The four things worth waking up for lead: sites expiring soon, sites already expired, failed
 * deployments, and certificates approaching renewal. Everything else is a count.
 */
@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [RouterLink, PageHeader, StatusPill, RelativePipe, DateTimePipe],
  templateUrl: './dashboard.html',
  styleUrl: './dashboard.css',
})
export class Dashboard implements OnInit {
  private readonly sitesApi = inject(SitesApi);
  private readonly certificatesApi = inject(CertificatesApi);
  private readonly deploymentsApi = inject(DeploymentsApi);
  private readonly instancesApi = inject(InstancesApi);
  private readonly notifications = inject(Notifications);

  readonly loading = signal(true);
  readonly sites = signal<SiteSummary[]>([]);
  readonly certificates = signal<Certificate[]>([]);
  readonly deployments = signal<Deployment[]>([]);
  readonly instances = signal<NginxInstance[]>([]);

  private static readonly EXPIRING_WINDOW_SECONDS = 14 * 86400;

  readonly active = computed(() => this.sites().filter((site) => site.status === 'ACTIVE').length);
  readonly expired = computed(() => this.sites().filter((site) => site.status === 'EXPIRED'));
  readonly errored = computed(() => this.sites().filter((site) => site.status === 'ERROR'));

  /** Still serving, but not for long. Expired sites are counted separately; they are past warning. */
  readonly expiringSoon = computed(() =>
    this.sites()
      .filter(
        (site) =>
          site.secondsUntilExpiry !== null &&
          site.secondsUntilExpiry > 0 &&
          site.secondsUntilExpiry < Dashboard.EXPIRING_WINDOW_SECONDS,
      )
      .sort((a, b) => (a.secondsUntilExpiry ?? 0) - (b.secondsUntilExpiry ?? 0)),
  );

  readonly certificatesNeedingAttention = computed(() =>
    this.certificates()
      .filter((certificate) => certificate.status !== 'VALID')
      .sort((a, b) => (a.daysRemaining ?? 0) - (b.daysRemaining ?? 0)),
  );

  readonly failedDeployments = computed(() =>
    this.deployments().filter((deployment) => deployment.status === 'FAILED'),
  );

  readonly recentDeployments = computed(() => this.deployments().slice(0, 6));

  readonly offlineInstances = computed(() =>
    this.instances().filter((instance) => instance.status === 'OFFLINE' || instance.status === 'DEGRADED'),
  );

  /** Nothing wrong anywhere. Worth saying explicitly rather than showing four empty panels. */
  readonly allClear = computed(
    () =>
      !this.loading() &&
      this.expiringSoon().length === 0 &&
      this.expired().length === 0 &&
      this.errored().length === 0 &&
      this.failedDeployments().length === 0 &&
      this.certificatesNeedingAttention().length === 0,
  );

  ngOnInit(): void {
    // A page size large enough to summarise a small estate honestly. The counts below say
    // "of the most recent 200" rather than pretending to be totals the API did not return.
    this.sitesApi.list({ size: 200, sort: ['EXPIRES_AT,asc'] }).subscribe({
      next: (page) => {
        this.sites.set(page.content);
        this.loading.set(false);
      },
      error: (problem) => {
        this.notifications.problem(problem);
        this.loading.set(false);
      },
    });

    this.certificatesApi.list().subscribe({
      next: (certificates) => this.certificates.set(certificates),
      // Certificates need broader visibility than sites, so a scoped operator may be refused
      // here. That is not an error worth interrupting them with.
      error: () => this.certificates.set([]),
    });

    this.deploymentsApi.list(undefined, 0, 20).subscribe({
      next: (page) => this.deployments.set(page.content),
      error: () => this.deployments.set([]),
    });

    this.instancesApi.list().subscribe({
      next: (instances) => this.instances.set(instances),
      error: () => this.instances.set([]),
    });
  }
}
