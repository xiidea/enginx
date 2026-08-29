import { Component, OnInit, inject, signal } from '@angular/core';
import { DeploymentsApi, InstancesApi } from '../../core/api/resources';
import { Deployment, NginxInstance, Page } from '../../core/api/models';
import { AuthService } from '../../core/auth/auth.service';
import { Notifications } from '../../shared/notifications';
import { EmptyState, PageHeader } from '../../shared/page';
import { StatusPill } from '../../shared/status-pill';
import { DateTimePipe } from '../../shared/formatting';

@Component({
  selector: 'app-deployment-list',
  standalone: true,
  imports: [PageHeader, EmptyState, StatusPill, DateTimePipe],
  templateUrl: './deployment-list.html',
  styleUrl: './deployment-list.css',
})
export class DeploymentList implements OnInit {
  private readonly api = inject(DeploymentsApi);
  private readonly instancesApi = inject(InstancesApi);
  private readonly notifications = inject(Notifications);
  readonly auth = inject(AuthService);

  readonly loading = signal(true);
  readonly page = signal<Page<Deployment> | null>(null);
  readonly instances = signal<NginxInstance[]>([]);
  readonly expanded = signal<string | null>(null);

  ngOnInit(): void {
    this.instancesApi.list().subscribe({
      next: (instances) => this.instances.set(instances),
      error: () => this.instances.set([]),
    });
    this.reload();
  }

  reload(index = 0): void {
    this.loading.set(true);
    this.api.list(undefined, index, 25).subscribe({
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

  instanceName(id: string): string {
    return this.instances().find((instance) => instance.id === id)?.name ?? id.slice(0, 8);
  }

  toggle(deployment: Deployment): void {
    this.expanded.update((current) => (current === deployment.id ? null : deployment.id));
  }

  /**
   * Re-activates the bundle this deployment replaced.
   *
   * Never automatic: the platform's only self-directed revert is the agent restoring the previous
   * release when a reload fails, which is a refusal to leave the host broken rather than a
   * decision to change version.
   */
  rollback(deployment: Deployment): void {
    if (!confirm('Re-activate the configuration this deployment replaced?')) {
      return;
    }
    this.api.rollback(deployment.id).subscribe({
      next: () => {
        this.notifications.info('Rollback queued', 'The earlier bundle is being re-activated.');
        setTimeout(() => this.reload(this.page()?.page ?? 0), 2500);
      },
      error: (problem) => this.notifications.problem(problem),
    });
  }
}
