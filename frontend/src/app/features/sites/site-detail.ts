import { Component, OnInit, computed, inject, input, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { CertificatesApi, DeploymentsApi, GroupsApi, PermissionsApi, SitesApi } from '../../core/api/resources';
import { Certificate, Deployment, DomainGroup, EffectivePermission, Preview, Site, SiteNotificationSettings, UpstreamCheck } from '../../core/api/models';
import { groupSource } from '../../core/api/suggestions';
import { Notifications } from '../../shared/notifications';
import { PageHeader } from '../../shared/page';
import { StatusPill } from '../../shared/status-pill';
import { Typeahead } from '../../shared/typeahead';
import { BytesPipe, DateTimePipe, RelativePipe } from '../../shared/formatting';

@Component({
  selector: 'app-site-detail',
  standalone: true,
  imports: [RouterLink, FormsModule, PageHeader, StatusPill, RelativePipe, DateTimePipe, BytesPipe, Typeahead],
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
  private readonly groupsApi = inject(GroupsApi);
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
   * The groups this site is filed under.
   *
   * Worth showing on the site rather than only on the groups page: a grant made over a group
   * reaches this site, so "which groups am I in" is the same question as "who else can reach me".
   */
  readonly groups = signal<DomainGroup[]>([]);
  readonly groupsLoading = signal(true);
  readonly groupToAdd = signal('');
  readonly addingGroup = signal(false);
  readonly groupChoices = groupSource(this.groupsApi);

  /**
   * Who is told when this site is about to expire.
   *
   * Loaded separately from the site because it is not part of its configuration: changing an
   * address must not take the site's optimistic lock or read as a configuration change.
   */
  readonly notificationSettings = signal<SiteNotificationSettings | null>(null);
  readonly expiryNotifications = signal(true);
  readonly subscribers = signal<string[]>([]);
  readonly newSubscriber = signal('');
  readonly savingNotifications = signal(false);

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
    this.loadGroups();
    this.loadNotifications();
  }

  private loadNotifications(): void {
    this.api.notifications(this.id()).subscribe({
      next: (settings) => {
        this.notificationSettings.set(settings);
        this.expiryNotifications.set(settings.expiryEnabled);
        this.subscribers.set([...settings.subscribers]);
      },
      // Not fatal: the rest of the page is about what the site serves, and a section that could
      // not load should not take the page down with it.
      error: () => this.notificationSettings.set(null),
    });
  }

  addSubscriber(): void {
    const address = this.newSubscriber().trim().toLowerCase();
    if (!address) {
      return;
    }
    // Deduplicated here as well as on the server, so the list a person is looking at never shows
    // the same address twice while they are still editing it.
    if (!this.subscribers().includes(address)) {
      this.subscribers.update((current) => [...current, address]);
    }
    this.newSubscriber.set('');
  }

  removeSubscriber(address: string): void {
    this.subscribers.update((current) => current.filter((entry) => entry !== address));
  }

  /** True while the form differs from what the server last returned. */
  notificationsChanged(): boolean {
    const saved = this.notificationSettings();
    if (!saved) {
      return false;
    }
    const same = saved.subscribers.length === this.subscribers().length
      && saved.subscribers.every((address) => this.subscribers().includes(address));
    return saved.expiryEnabled !== this.expiryNotifications() || !same;
  }

  saveNotifications(): void {
    this.savingNotifications.set(true);
    this.api
      .configureNotifications(this.id(), {
        expiryEnabled: this.expiryNotifications(),
        subscribers: this.subscribers(),
      })
      .subscribe({
        next: (settings) => {
          this.savingNotifications.set(false);
          this.notificationSettings.set(settings);
          this.subscribers.set([...settings.subscribers]);
          this.notifications.success('Notification settings saved');
        },
        error: (problem) => {
          this.savingNotifications.set(false);
          this.notifications.problem(problem);
        },
      });
  }


  private loadGroups(): void {
    this.groupsLoading.set(true);
    this.groupsApi.list(this.id()).subscribe({
      next: (groups) => {
        this.groups.set(groups);
        this.groupsLoading.set(false);
      },
      error: () => {
        this.groups.set([]);
        this.groupsLoading.set(false);
      },
    });
  }

  addToGroup(): void {
    const groupId = this.groupToAdd();
    if (!groupId) {
      return;
    }
    this.addingGroup.set(true);
    this.groupsApi.addMember(groupId, this.id()).subscribe({
      next: () => {
        this.addingGroup.set(false);
        this.groupToAdd.set('');
        this.notifications.success('Added to group', 'Grants on that group now reach this site.');
        this.loadGroups();
      },
      error: (problem) => {
        this.addingGroup.set(false);
        this.notifications.problem(problem);
      },
    });
  }

  removeFromGroup(group: DomainGroup): void {
    if (!confirm(`Remove this site from ${group.path}? Access granted through that group ends.`)) {
      return;
    }
    this.groupsApi.removeMember(group.id, this.id()).subscribe({
      next: () => {
        this.notifications.success('Removed from group', group.path);
        this.loadGroups();
      },
      error: (problem) => this.notifications.problem(problem),
    });
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
