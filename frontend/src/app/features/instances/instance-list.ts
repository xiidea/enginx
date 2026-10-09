import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AgentRegistrationApi, InstancesApi } from '../../core/api/resources';
import { AgentJob, AgentRegistrationToken, NginxInstance, PushTransport } from '../../core/api/models';
import { AuthService } from '../../core/auth/auth.service';
import { Notifications } from '../../shared/notifications';
import { EmptyState, PageHeader } from '../../shared/page';
import { StatusPill } from '../../shared/status-pill';
import { DateTimePipe } from '../../shared/formatting';
import { environment } from '../../../environments/environment';

@Component({
  selector: 'app-instance-list',
  standalone: true,
  imports: [FormsModule, PageHeader, EmptyState, StatusPill, DateTimePipe],
  templateUrl: './instance-list.html',
  styleUrl: './instance-list.css',
})
export class InstanceList implements OnInit {
  private readonly api = inject(InstancesApi);
  private readonly enrolment = inject(AgentRegistrationApi);
  private readonly notifications = inject(Notifications);
  readonly auth = inject(AuthService);

  readonly loading = signal(true);
  readonly instances = signal<NginxInstance[]>([]);
  readonly registering = signal(false);
  readonly showForm = signal(false);

  /**
   * How a new host will be reached. Push needs a URL to dial and a certificate to pin; pull needs
   * neither, because nothing ever dials it.
   */
  readonly mode = signal<'PUSH' | 'PULL'>('PUSH');

  /** The host whose queued work is on screen, and what it holds. */
  readonly expandedHost = signal<string | null>(null);
  readonly jobs = signal<AgentJob[]>([]);
  readonly jobsLoading = signal(false);

  readonly tokens = signal<AgentRegistrationToken[]>([]);
  readonly tokensLoading = signal(false);
  readonly minting = signal(false);
  readonly tokenDescription = signal('');
  readonly tokenMaxUses = signal('1');
  readonly tokenExpiresAt = signal('');

  /**
   * The token just minted, in clear.
   *
   * Held only until the operator navigates away. The platform stores a digest and cannot show it
   * again, so the one moment it exists anywhere readable is right here.
   */
  readonly mintedToken = signal<string | null>(null);
  readonly copied = signal(false);

  readonly name = signal('');
  readonly hostname = signal('');
  readonly agentBaseUrl = signal('https://');
  readonly fingerprint = signal('');
  readonly pushTransport = signal<PushTransport>('MTLS');
  readonly agentAuthToken = signal('');
  readonly showAuthToken = signal(false);
  readonly environment = signal('PRODUCTION');
  /** Off for a host that already runs NGINX with its own default server. */
  readonly hostDefaultServer = signal(false);

  ngOnInit(): void {
    this.reload();
    if (this.auth.isSuperAdmin()) {
      this.reloadTokens();
    }
  }

  /**
   * Shows or hides a host's queued work.
   *
   * Loaded on expansion rather than with the list: most of the time nobody opens any of them, and
   * a request per host would make the page cost proportional to the size of the estate.
   */
  toggleJobs(instance: NginxInstance): void {
    if (this.expandedHost() === instance.id) {
      this.expandedHost.set(null);
      return;
    }
    this.expandedHost.set(instance.id);
    this.jobsLoading.set(true);
    this.jobs.set([]);

    this.api.agentJobs(instance.id).subscribe({
      next: (jobs) => {
        this.jobs.set(jobs);
        this.jobsLoading.set(false);
      },
      error: (problem) => {
        this.jobsLoading.set(false);
        this.notifications.problem(problem);
      },
    });
  }

  /** Work that has not finished, which is what makes a stalled deployment legible. */
  outstanding(): number {
    return this.jobs().filter((job) => job.status === 'QUEUED' || job.status === 'LEASED').length;
  }

  reloadTokens(): void {
    this.tokensLoading.set(true);
    this.enrolment.list().subscribe({
      next: (tokens) => {
        this.tokens.set(tokens);
        this.tokensLoading.set(false);
      },
      error: (problem) => {
        this.tokensLoading.set(false);
        this.notifications.problem(problem);
      },
    });
  }

  mintToken(): void {
    this.minting.set(true);
    this.mintedToken.set(null);

    const maxUses = Number.parseInt(this.tokenMaxUses(), 10);
    this.enrolment
      .create({
        description: this.tokenDescription().trim() || undefined,
        // An empty box means no limit, which is a choice an operator has to make deliberately
        // rather than one that happens by leaving a field alone.
        maxUses: Number.isFinite(maxUses) && maxUses > 0 ? maxUses : null,
        expiresAt: this.tokenExpiresAt() ? new Date(this.tokenExpiresAt()).toISOString() : null,
      })
      .subscribe({
        next: (created) => {
          this.minting.set(false);
          this.mintedToken.set(created.token);
          this.copied.set(false);
          this.tokenDescription.set('');
          this.reloadTokens();
        },
        error: (problem) => {
          this.minting.set(false);
          this.notifications.problem(problem);
        },
      });
  }

  revokeToken(token: AgentRegistrationToken): void {
    if (!confirm('Revoke this registration token?\n\nHosts it has already enrolled keep working: '
        + 'they hold credentials of their own.')) {
      return;
    }
    this.enrolment.revoke(token.id).subscribe({
      next: () => {
        this.notifications.success('Token revoked');
        this.reloadTokens();
      },
      error: (problem) => this.notifications.problem(problem),
    });
  }

  /**
   * The command an operator runs on the new host, with the token already in it.
   *
   * The API base is the one this console talks to, and the image is the release this console is,
   * so a host enrolled from it runs the same version as the platform it joins.
   */
  runCommand(): string {
    const tag = environment.version === 'dev' ? 'latest' : environment.version;
    return [
      'docker run -d --name enginx-agent \\',
      `  -e ENGINX_SERVER_URL=${environment.apiBase} \\`,
      `  -e ENGINX_REGISTRATION_TOKEN=${this.mintedToken()} \\`,
      '  -e ENGINX_INSTANCE_NAME=nginx-edge-01 \\',
      '  -v /var/lib/enginx:/var/lib/enginx \\',
      '  -p 80:80 -p 443:443 \\',
      `  ghcr.io/xiidea/enginx-agent:${tag}`,
    ].join('\n');
  }

  copyCommand(): void {
    navigator.clipboard.writeText(this.runCommand()).then(
      () => this.copied.set(true),
      // Clipboard access can be refused, and silently doing nothing would look like a broken
      // button. The command is on screen either way.
      () => this.notifications.info('Could not copy', 'Select the command and copy it manually.'),
    );
  }

  dismissToken(): void {
    this.mintedToken.set(null);
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

  setPushTransport(transport: PushTransport): void {
    this.pushTransport.set(transport);
    // An untouched prefix follows the choice back to HTTPS. A typed URL is left alone.
    if (transport === 'MTLS' && this.agentBaseUrl() === 'http://') {
      this.agentBaseUrl.set('https://');
    }
  }

  urlPlaceholder(): string {
    return this.pushTransport() === 'MTLS' ? 'https://nginx-01.internal:8443' : 'https://nginx-01.internal:8080';
  }

  /**
   * A token host reached over plain HTTP. Allowed, because a proxy on the same machine may be what
   * terminates TLS — but said plainly, since otherwise the token and every bundle, private keys
   * included, cross the network readable.
   */
  insecureTokenUrl(): boolean {
    return this.pushTransport() === 'HTTP_TOKEN' && /^http:\/\//i.test(this.agentBaseUrl().trim());
  }

  resetForm(): void {
    this.name.set('');
    this.hostname.set('');
    this.agentBaseUrl.set('https://');
    this.fingerprint.set('');
    this.agentAuthToken.set('');
    this.showAuthToken.set(false);
    this.pushTransport.set('MTLS');
    this.environment.set('PRODUCTION');
    this.hostDefaultServer.set(false);
  }

  register(): void {
    this.registering.set(true);
    const transport = this.pushTransport();
    const payload: {
      name: string;
      hostname: string;
      agentBaseUrl: string;
      pushTransport: PushTransport;
      agentCertFingerprint?: string;
      agentAuthToken?: string;
      environment: string;
      defaultServerManaged: boolean;
    } = {
      name: this.name().trim(),
      hostname: this.hostname().trim(),
      agentBaseUrl: this.agentBaseUrl().trim(),
      pushTransport: transport,
      environment: this.environment(),
      defaultServerManaged: !this.hostDefaultServer(),
    };

    if (transport === 'MTLS') {
      payload.agentCertFingerprint = this.fingerprint().replace(/[\s:]/g, '').toUpperCase();
    } else {
      payload.agentAuthToken = this.agentAuthToken().trim();
    }

    this.api
      .register(payload)
      .subscribe({
        next: (instance) => {
          this.registering.set(false);
          this.showForm.set(false);
          this.notifications.success('Instance registered', instance.name);
          this.resetForm();
          this.reload();
        },
        error: (problem) => {
          this.registering.set(false);
          this.notifications.problem(problem);
        },
      });
  }

  /** Switches who answers names no site matches; effective from the next deployment. */
  toggleDefaultServer(instance: NginxInstance): void {
    const managed = !instance.defaultServerManaged;
    const question = managed
      ? `Have the platform answer unmatched names on ${instance.name} with its own catch-all? ` +
        'This fails validation if the host still has its own default server.'
      : `Let ${instance.name}'s own default server answer unmatched names? ` +
        'The platform stops rendering its catch-all from the next deployment.';
    if (!confirm(question)) {
      return;
    }
    this.api.setDefaultServer(instance.id, managed).subscribe({
      next: () => {
        this.notifications.success('Saved. Deploy to apply', instance.name);
        this.reload();
      },
      error: (problem) => this.notifications.problem(problem),
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
