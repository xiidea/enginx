import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { CertificatesApi } from '../../core/api/resources';
import { Certificate } from '../../core/api/models';
import { AuthService } from '../../core/auth/auth.service';
import { Notifications } from '../../shared/notifications';
import { EmptyState, PageHeader } from '../../shared/page';
import { StatusPill } from '../../shared/status-pill';
import { DateTimePipe } from '../../shared/formatting';

type Mode = 'none' | 'request' | 'upload';

@Component({
  selector: 'app-certificate-list',
  standalone: true,
  imports: [FormsModule, PageHeader, EmptyState, StatusPill, DateTimePipe],
  templateUrl: './certificate-list.html',
  styleUrl: './certificate-list.css',
})
export class CertificateList implements OnInit {
  private readonly api = inject(CertificatesApi);
  private readonly notifications = inject(Notifications);
  readonly auth = inject(AuthService);

  readonly loading = signal(true);
  readonly busy = signal(false);
  readonly certificates = signal<Certificate[]>([]);
  readonly mode = signal<Mode>('none');

  readonly requestName = signal('');
  readonly requestDomains = signal('');
  readonly requestAutoRenew = signal(true);
  readonly requestRenewBefore = signal(30);

  readonly uploadName = signal('');
  readonly uploadChain = signal('');
  readonly uploadKey = signal('');

  /** Anything not simply VALID is listed first: this page exists to surface what needs doing. */
  readonly ordered = computed(() =>
    [...this.certificates()].sort((a, b) => {
      const rank = (certificate: Certificate) => (certificate.status === 'VALID' ? 1 : 0);
      return rank(a) - rank(b) || (a.daysRemaining ?? 1e9) - (b.daysRemaining ?? 1e9);
    }),
  );

  ngOnInit(): void {
    this.reload();
  }

  reload(): void {
    this.loading.set(true);
    this.api.list().subscribe({
      next: (certificates) => {
        this.certificates.set(certificates);
        this.loading.set(false);
      },
      error: (problem) => {
        this.notifications.problem(problem);
        this.loading.set(false);
      },
    });
  }

  request(): void {
    const domains = this.requestDomains()
      .split(/[\s,]+/)
      .map((domain) => domain.trim())
      .filter(Boolean);

    if (!domains.length) {
      return;
    }
    this.busy.set(true);
    this.api
      .request({
        name: this.requestName().trim(),
        domains,
        autoRenew: this.requestAutoRenew(),
        renewBeforeDays: this.requestRenewBefore(),
      })
      .subscribe({
        next: (certificate) => {
          this.busy.set(false);
          this.mode.set('none');
          this.requestName.set('');
          this.requestDomains.set('');
          // Issuance either succeeded or recorded why it did not; both are worth reporting
          // plainly rather than claiming success.
          if (certificate.status === 'ERROR') {
            this.notifications.problem({
              type: 'about:blank',
              title: 'The certificate could not be issued',
              status: 200,
              detail: certificate.lastError ?? 'The authority did not issue a certificate.',
            });
          } else {
            this.notifications.success('Certificate issued', certificate.domains.join(', '));
          }
          this.reload();
        },
        error: (problem) => {
          this.busy.set(false);
          this.notifications.problem(problem);
        },
      });
  }

  upload(): void {
    this.busy.set(true);
    this.api
      .upload({
        name: this.uploadName().trim(),
        fullChainPem: this.uploadChain(),
        privateKeyPem: this.uploadKey(),
      })
      .subscribe({
        next: (certificate) => {
          this.busy.set(false);
          this.mode.set('none');
          // Cleared immediately: there is no reason for key material to sit in a form field
          // after it has been stored.
          this.uploadName.set('');
          this.uploadChain.set('');
          this.uploadKey.set('');
          this.notifications.success('Certificate stored', certificate.domains.join(', '));
          this.reload();
        },
        error: (problem) => {
          this.busy.set(false);
          this.notifications.problem(problem);
        },
      });
  }

  renew(certificate: Certificate): void {
    this.api.renew(certificate.id).subscribe({
      next: () => {
        this.notifications.success('Renewed', certificate.name);
        this.reload();
      },
      error: (problem) => this.notifications.problem(problem),
    });
  }

  toggleAutoRenew(certificate: Certificate): void {
    this.api.configureRenewal(certificate.id, !certificate.autoRenew, certificate.renewBeforeDays).subscribe({
      next: () => this.reload(),
      error: (problem) => this.notifications.problem(problem),
    });
  }

  revoke(certificate: Certificate): void {
    if (!confirm(`Revoke ${certificate.name}? It will stop being deployed and cannot be un-revoked.`)) {
      return;
    }
    this.api.revoke(certificate.id).subscribe({
      next: () => {
        this.notifications.success('Revoked', certificate.name);
        this.reload();
      },
      error: (problem) => this.notifications.problem(problem),
    });
  }

  remove(certificate: Certificate): void {
    if (!confirm(`Delete ${certificate.name}?`)) {
      return;
    }
    this.api.remove(certificate.id).subscribe({
      next: () => {
        this.notifications.success('Deleted', certificate.name);
        this.reload();
      },
      error: (problem) => this.notifications.problem(problem),
    });
  }
}
