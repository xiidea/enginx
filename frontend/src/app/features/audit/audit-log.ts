import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AuditApi, AuditQuery } from '../../core/api/resources';
import { AuditEntry, Page } from '../../core/api/models';
import { AuthService } from '../../core/auth/auth.service';
import { Notifications } from '../../shared/notifications';
import { EmptyState, PageHeader } from '../../shared/page';
import { DateTimePipe } from '../../shared/formatting';

/**
 * The audit trail.
 *
 * Read-only by construction, not by convention: there is no endpoint to call that would change an
 * entry. The page's job is to make a specific question answerable — who changed this domain, when
 * did that certificate get revoked, what was refused — which is why the filters are the first
 * thing on the page rather than a panel to be discovered.
 */
@Component({
  selector: 'app-audit-log',
  standalone: true,
  imports: [FormsModule, PageHeader, EmptyState, DateTimePipe],
  templateUrl: './audit-log.html',
  styleUrl: './audit-log.css',
})
export class AuditLog implements OnInit {
  private readonly api = inject(AuditApi);
  private readonly notifications = inject(Notifications);
  readonly auth = inject(AuthService);

  readonly loading = signal(true);
  readonly page = signal<Page<AuditEntry> | null>(null);
  readonly actions = signal<string[]>([]);
  readonly expanded = signal<string | null>(null);
  /**
   * Set when the server refused the request. Kept separate from an empty result because the two
   * mean opposite things: "there is nothing here" and "you were not allowed to look".
   */
  readonly refused = signal(false);

  readonly actor = signal('');
  readonly action = signal('');
  readonly result = signal('');
  readonly from = signal('');
  readonly to = signal('');

  readonly filtered = computed(
    () => !!(this.actor() || this.action() || this.result() || this.from() || this.to()),
  );

  ngOnInit(): void {
    this.api.actions().subscribe({
      next: (actions) => this.actions.set(actions),
      error: () => this.actions.set([]),
    });
    this.reload();
  }

  reload(index = 0): void {
    this.loading.set(true);
    this.refused.set(false);
    this.api.search(this.query(index)).subscribe({
      next: (page) => {
        this.page.set(page);
        this.loading.set(false);
      },
      error: (problem) => {
        this.notifications.problem(problem);
        this.refused.set(problem?.status === 403);
        this.page.set(null);
        this.loading.set(false);
      },
    });
  }

  clear(): void {
    this.actor.set('');
    this.action.set('');
    this.result.set('');
    this.from.set('');
    this.to.set('');
    this.reload();
  }

  toggle(entry: AuditEntry): void {
    this.expanded.update((current) => (current === entry.id ? null : entry.id));
  }

  /** Whether an entry has anything to show when expanded. */
  hasDetail(entry: AuditEntry): boolean {
    return !!(entry.beforeState || entry.afterState || entry.errorMessage);
  }

  format(state: Record<string, unknown> | null): string {
    return state ? JSON.stringify(state, null, 2) : '—';
  }

  private query(index: number): AuditQuery {
    return {
      actor: this.actor().trim() || undefined,
      action: this.action() ? [this.action()] : undefined,
      result: this.result() ? [this.result()] : undefined,
      // The API takes instants. A date input gives a local day, so the range is widened to that
      // whole day rather than to its midnight, which would silently exclude everything on it.
      from: this.from() ? new Date(`${this.from()}T00:00:00`).toISOString() : undefined,
      to: this.to() ? new Date(`${this.to()}T23:59:59.999`).toISOString() : undefined,
      page: index,
      size: 50,
    };
  }
}
