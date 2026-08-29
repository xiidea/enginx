import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DirectoryEntry } from '../../core/api/models';
import { PermissionsApi } from '../../core/api/resources';
import { Notifications } from '../../shared/notifications';
import { EmptyState, PageHeader } from '../../shared/page';
import { DateTimePipe } from '../../shared/formatting';

/**
 * The directory of everyone who has signed in, and the removal of entries that no longer earn
 * their place.
 *
 * <p>Worth being clear about what this page does, because the wording matters to whoever uses it:
 * an entry is a *name*, offered when authoring a permission grant. Removing one takes away the
 * name. It does not revoke access, does not delete an account, and is undone by its owner simply
 * signing in again.
 */
@Component({
  selector: 'app-directory',
  standalone: true,
  imports: [FormsModule, PageHeader, EmptyState, DateTimePipe],
  templateUrl: './directory.html',
  styleUrl: './directory.css',
})
export class Directory implements OnInit {
  private static readonly PAGE_SIZE = 25;

  /** Mirrors the server's floor, so the obvious mistake is caught without a round trip. */
  readonly minimumDormancy = 30;

  private readonly api = inject(PermissionsApi);
  private readonly notifications = inject(Notifications);

  readonly entries = signal<DirectoryEntry[]>([]);
  readonly total = signal(0);
  readonly page = signal(0);
  readonly loading = signal(true);
  readonly cleaning = signal(false);

  readonly search = signal('');
  readonly staleOnly = signal(false);
  /** Empty means "only entries that provably no longer resolve". */
  readonly dormantForDays = signal<string>('');

  readonly totalPages = computed(() => Math.ceil(this.total() / Directory.PAGE_SIZE));
  readonly staleShown = computed(() => this.entries().filter((entry) => !entry.present).length);

  private timer: ReturnType<typeof setTimeout> | null = null;

  ngOnInit(): void {
    this.reload();
  }

  /** Debounced, so typing in the search box is one request rather than one per keystroke. */
  onSearch(text: string): void {
    this.search.set(text);
    if (this.timer) {
      clearTimeout(this.timer);
    }
    this.timer = setTimeout(() => this.reload(0), 250);
  }

  reload(page = this.page()): void {
    this.loading.set(true);
    this.api
      .users({
        search: this.search().trim(),
        stale: this.staleOnly() || undefined,
        dormantForDays: this.dormancy(),
        page,
        size: Directory.PAGE_SIZE,
      })
      .subscribe({
        next: (result) => {
          this.entries.set(result.content);
          this.total.set(result.totalElements);
          this.page.set(result.page);
          this.loading.set(false);
        },
        error: (problem) => {
          this.loading.set(false);
          this.notifications.problem(problem);
        },
      });
  }

  toggleStale(): void {
    this.staleOnly.set(!this.staleOnly());
    this.reload(0);
  }

  onDormancyChange(value: string): void {
    this.dormantForDays.set(value);
    this.reload(0);
  }

  forget(entry: DirectoryEntry): void {
    const warning = entry.hasGrants
      ? `\n\n${entry.displayName} is named by a permission grant. Removing the name leaves that grant showing a bare identifier. The grant itself is not revoked.`
      : '';
    if (!confirm(`Remove ${entry.displayName} from the directory?${warning}`)) {
      return;
    }
    this.api.forget(entry.subjectRef).subscribe({
      next: () => {
        this.notifications.success('Removed from the directory', entry.displayName);
        this.reload();
      },
      error: (problem) => this.notifications.problem(problem),
    });
  }

  cleanup(includeGranted: boolean): void {
    const scope = this.dormancy()
      ? `every departed account and everyone unseen for ${this.dormancy()} days`
      : 'every departed account';
    const granted = includeGranted
      ? '\n\nEntries named by a permission grant will be removed too. Those grants are not revoked, but will show a bare identifier.'
      : '';
    if (!confirm(`Remove ${scope} from the directory?${granted}`)) {
      return;
    }

    this.cleaning.set(true);
    this.api.cleanupDirectory({ dormantForDays: this.dormancy(), includeGranted }).subscribe({
      next: (result) => {
        this.cleaning.set(false);
        const skipped = result.skippedBecauseGranted
          ? `${result.skippedBecauseGranted} kept because a grant still names them.`
          : undefined;
        this.notifications.success(
          result.removed ? `Removed ${result.removed} entries` : 'Nothing to remove',
          skipped,
        );
        this.reload(0);
      },
      error: (problem) => {
        this.cleaning.set(false);
        this.notifications.problem(problem);
      },
    });
  }

  /** Null rather than 0 when the box is empty: dormancy is opt-in, not a cutoff of zero days. */
  private dormancy(): number | null {
    const value = Number.parseInt(this.dormantForDays(), 10);
    return Number.isFinite(value) && value > 0 ? value : null;
  }
}
