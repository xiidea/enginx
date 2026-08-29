import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { InstancesApi } from '../../core/api/resources';
import { NginxInstance } from '../../core/api/models';
import { AuthService } from '../../core/auth/auth.service';
import { Notifications } from '../../shared/notifications';
import { EmptyState, PageHeader } from '../../shared/page';
import { StatusPill } from '../../shared/status-pill';
import { DateTimePipe } from '../../shared/formatting';

@Component({
  selector: 'app-instance-list',
  standalone: true,
  imports: [FormsModule, PageHeader, EmptyState, StatusPill, DateTimePipe],
  templateUrl: './instance-list.html',
  styleUrl: './instance-list.css',
})
export class InstanceList implements OnInit {
  private readonly api = inject(InstancesApi);
  private readonly notifications = inject(Notifications);
  readonly auth = inject(AuthService);

  readonly loading = signal(true);
  readonly instances = signal<NginxInstance[]>([]);
  readonly registering = signal(false);
  readonly showForm = signal(false);

  readonly name = signal('');
  readonly hostname = signal('');
  readonly agentBaseUrl = signal('https://');
  readonly fingerprint = signal('');
  readonly environment = signal('PRODUCTION');

  ngOnInit(): void {
    this.reload();
  }

  reload(): void {
    this.loading.set(true);
    this.api.list().subscribe({
      next: (instances) => {
        this.instances.set(instances);
        this.loading.set(false);
      },
      error: (problem) => {
        this.notifications.problem(problem);
        this.loading.set(false);
      },
    });
  }

  register(): void {
    this.registering.set(true);
    this.api
      .register({
        name: this.name().trim(),
        hostname: this.hostname().trim(),
        agentBaseUrl: this.agentBaseUrl().trim(),
        // Normalised here as a convenience; the server validates the shape regardless.
        agentCertFingerprint: this.fingerprint().replace(/[\s:]/g, '').toUpperCase(),
        environment: this.environment(),
      })
      .subscribe({
        next: (instance) => {
          this.registering.set(false);
          this.showForm.set(false);
          this.notifications.success('Instance registered', instance.name);
          this.reload();
        },
        error: (problem) => {
          this.registering.set(false);
          this.notifications.problem(problem);
        },
      });
  }

  deploy(instance: NginxInstance): void {
    if (!confirm(`Republish every site on ${instance.name}?`)) {
      return;
    }
    this.api.deploy(instance.id).subscribe({
      next: () => this.notifications.info('Deployment queued', instance.name),
      error: (problem) => this.notifications.problem(problem),
    });
  }
}
