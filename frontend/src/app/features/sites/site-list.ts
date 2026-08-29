import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { SitesApi, SiteQuery } from '../../core/api/resources';
import { InstancesApi } from '../../core/api/resources';
import { NginxInstance, Page, SiteStatus, SiteSummary } from '../../core/api/models';
import { AuthService } from '../../core/auth/auth.service';
import { Notifications } from '../../shared/notifications';
import { EmptyState, PageHeader } from '../../shared/page';
import { StatusPill } from '../../shared/status-pill';
import { DateTimePipe, RelativePipe } from '../../shared/formatting';

const ALL_STATUSES: SiteStatus[] = ['ACTIVE', 'PENDING', 'DISABLED', 'EXPIRED', 'ERROR'];

@Component({
  selector: 'app-site-list',
  standalone: true,
  imports: [FormsModule, RouterLink, PageHeader, EmptyState, StatusPill, RelativePipe, DateTimePipe],
  templateUrl: './site-list.html',
  styleUrl: './site-list.css',
})
export class SiteList implements OnInit {
  private readonly api = inject(SitesApi);
  private readonly instancesApi = inject(InstancesApi);
  private readonly notifications = inject(Notifications);
  readonly auth = inject(AuthService);

  readonly statuses = ALL_STATUSES;
  readonly loading = signal(true);
  readonly page = signal<Page<SiteSummary> | null>(null);
  readonly instances = signal<NginxInstance[]>([]);

  readonly search = signal('');
  readonly status = signal<SiteStatus | ''>('');
  readonly instanceId = signal('');
  readonly sortField = signal('DOMAIN');
  readonly sortDirection = signal<'asc' | 'desc'>('asc');
  readonly pageIndex = signal(0);
  readonly pageSize = signal(25);

  readonly instanceName = computed(() => {
    const byId = new Map(this.instances().map((instance) => [instance.id, instance.name]));
    return (id: string) => byId.get(id) ?? id.slice(0, 8);
  });

  readonly rangeLabel = computed(() => {
    const current = this.page();
    if (!current || current.totalElements === 0) {
      return '';
    }
    const from = current.page * current.size + 1;
    const to = Math.min(from + current.content.length - 1, current.totalElements);
    return `${from}–${to} of ${current.totalElements}`;
  });

  ngOnInit(): void {
    this.instancesApi.list().subscribe({
      next: (instances) => this.instances.set(instances),
      error: () => this.instances.set([]),
    });
    this.reload();
  }

  reload(): void {
    this.loading.set(true);
    const query: SiteQuery = {
      search: this.search() || undefined,
      status: this.status() ? [this.status() as SiteStatus] : undefined,
      nginxInstanceId: this.instanceId() || undefined,
      page: this.pageIndex(),
      size: this.pageSize(),
      // The API resolves the field against an enum, so an unrecognised value is refused rather
      // than reaching the database as a property path.
      sort: [`${this.sortField()},${this.sortDirection()}`],
    };

    this.api.list(query).subscribe({
      next: (page) => {
        this.page.set(page);
        this.loading.set(false);
      },
      error: (problem) => {
        this.notifications.problem(problem);
        this.loading.set(false);
      },
    });
  }

  applyFilters(): void {
    // Filtering resets to the first page: staying on page four of a narrower result set would
    // usually show nothing and look like a failure.
    this.pageIndex.set(0);
    this.reload();
  }

  sortBy(field: string): void {
    if (this.sortField() === field) {
      this.sortDirection.update((direction) => (direction === 'asc' ? 'desc' : 'asc'));
    } else {
      this.sortField.set(field);
      this.sortDirection.set('asc');
    }
    this.reload();
  }

  goToPage(index: number): void {
    this.pageIndex.set(Math.max(0, index));
    this.reload();
  }

  toggle(site: SiteSummary): void {
    const request = site.enabled ? this.api.disable(site.id) : this.api.enable(site.id);
    request.subscribe({
      next: (updated) => {
        this.notifications.success(`${site.domain} ${updated.enabled ? 'enabled' : 'disabled'}`);
        this.reload();
      },
      error: (problem) => this.notifications.problem(problem),
    });
  }

  deploy(site: SiteSummary): void {
    this.api.deploy(site.id).subscribe({
      next: () =>
        this.notifications.info(
          'Deployment queued',
          'The configuration is rendered and applied in the background. Watch it on the Deployments page.',
        ),
      error: (problem) => this.notifications.problem(problem),
    });
  }

  /** Highlights a site that is close to expiry but still serving. */
  isExpiringSoon(site: SiteSummary): boolean {
    return site.secondsUntilExpiry !== null && site.secondsUntilExpiry > 0 && site.secondsUntilExpiry < 14 * 86400;
  }
}
