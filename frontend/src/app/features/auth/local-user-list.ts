import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { GLOBAL_ROLES, LocalUser } from '../../core/api/models';
import { LocalUsersApi } from '../../core/api/resources';
import { AuthService } from '../../core/auth/auth.service';
import { Notifications } from '../../shared/notifications';
import { DateTimePipe } from '../../shared/formatting';
import { EmptyState, PageHeader } from '../../shared/page';

/**
 * Administering the accounts the platform authenticates itself.
 *
 * Only reachable with a global admin role, and only useful when local authentication is switched
 * on. Both are the server's decisions; this page hides what it can as a courtesy.
 */
@Component({
  selector: 'app-local-user-list',
  standalone: true,
  imports: [FormsModule, PageHeader, EmptyState, DateTimePipe],
  templateUrl: './local-user-list.html',
  styleUrl: './local-user-list.css',
})
export class LocalUserList implements OnInit {
  readonly roles = GLOBAL_ROLES;

  private readonly api = inject(LocalUsersApi);
  private readonly notifications = inject(Notifications);
  readonly auth = inject(AuthService);

  readonly loading = signal(true);
  readonly users = signal<LocalUser[]>([]);
  readonly showForm = signal(false);
  readonly saving = signal(false);

  readonly username = signal('');
  readonly password = signal('');
  readonly email = signal('');
  readonly displayName = signal('');
  readonly selectedRoles = signal<ReadonlySet<string>>(new Set(['READ_ONLY']));
  readonly groupPaths = signal('');

  /** The account whose password is being reset, if any. */
  readonly resetting = signal<LocalUser | null>(null);
  readonly resetPassword = signal('');

  ngOnInit(): void {
    this.reload();
  }

  reload(): void {
    this.loading.set(true);
    this.api.list().subscribe({
      next: (users) => {
        this.users.set(users);
        this.loading.set(false);
      },
      error: (problem) => {
        this.notifications.problem(problem);
        this.loading.set(false);
      },
    });
  }

  toggleRole(role: string): void {
    this.selectedRoles.update((current) => {
      const next = new Set(current);
      if (!next.delete(role)) {
        next.add(role);
      }
      return next;
    });
  }

  create(): void {
    this.saving.set(true);
    this.api
      .create({
        username: this.username().trim(),
        password: this.password(),
        email: this.email().trim() || undefined,
        displayName: this.displayName().trim() || undefined,
        roles: [...this.selectedRoles()],
        groupPaths: splitPaths(this.groupPaths()),
        // Set by an administrator, so the administrator knows it. Forcing the change means the
        // account ends up with a password only its owner has ever seen.
        mustChangePassword: true,
      })
      .subscribe({
        next: (user) => {
          this.saving.set(false);
          this.showForm.set(false);
          this.resetCreateForm();
          this.notifications.success('Account created', `${user.username} must change its password at first sign-in.`);
          this.reload();
        },
        error: (problem) => {
          this.saving.set(false);
          this.notifications.problem(problem);
        },
      });
  }

  setEnabled(user: LocalUser, enabled: boolean): void {
    this.api.setEnabled(user.id, enabled).subscribe({
      next: () => {
        this.notifications.success(enabled ? 'Account enabled' : 'Account disabled', user.username);
        this.reload();
      },
      error: (problem) => this.notifications.problem(problem),
    });
  }

  applyReset(): void {
    const user = this.resetting();
    if (!user) {
      return;
    }
    this.saving.set(true);
    this.api.changePassword(user.id, this.resetPassword()).subscribe({
      next: () => {
        this.saving.set(false);
        this.resetting.set(null);
        this.resetPassword.set('');
        this.notifications.success('Password reset', user.username);
        this.reload();
      },
      error: (problem) => {
        this.saving.set(false);
        this.notifications.problem(problem);
      },
    });
  }

  remove(user: LocalUser): void {
    if (!confirm(`Delete ${user.username}? Their permission grants are not removed with them.`)) {
      return;
    }
    this.api.remove(user.id).subscribe({
      next: () => {
        this.notifications.success('Account deleted', user.username);
        this.reload();
      },
      error: (problem) => this.notifications.problem(problem),
    });
  }

  /** True for the signed-in account, which cannot usefully delete or disable itself. */
  isSelf(user: LocalUser): boolean {
    return user.id === this.auth.localUserId();
  }

  private resetCreateForm(): void {
    this.username.set('');
    this.password.set('');
    this.email.set('');
    this.displayName.set('');
    this.selectedRoles.set(new Set(['READ_ONLY']));
    this.groupPaths.set('');
  }
}

function splitPaths(value: string): string[] {
  return value
    .split(/[\n,]/)
    .map((path) => path.trim())
    .filter(Boolean);
}
