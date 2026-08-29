import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { ApiService } from '../../core/api/api.service';
import { Problem } from '../../core/api/models';
import { AuthService } from '../../core/auth/auth.service';

/**
 * The screen an account is confined to until it replaces the password it was issued.
 *
 * This is not merely a prompt. The server refuses every other request from such a token, so there
 * is nothing else the account could usefully be shown.
 */
@Component({
  selector: 'app-password-change',
  standalone: true,
  imports: [FormsModule],
  templateUrl: './password-change.html',
  styleUrl: './sign-in.css',
})
export class PasswordChange {
  /** Mirrors the server's own rule, so the obvious mistake is caught without a round trip. */
  static readonly MINIMUM_LENGTH = 12;

  readonly minimumLength = PasswordChange.MINIMUM_LENGTH;

  private readonly api = inject(ApiService);
  private readonly auth = inject(AuthService);

  readonly currentPassword = signal('');
  readonly newPassword = signal('');
  readonly confirmation = signal('');
  readonly submitting = signal(false);
  readonly error = signal<string | null>(null);

  readonly username = this.auth.username;

  valid(): boolean {
    return (
      this.currentPassword().length > 0
      && this.newPassword().length >= PasswordChange.MINIMUM_LENGTH
      && this.newPassword() === this.confirmation()
    );
  }

  async submit(): Promise<void> {
    const id = this.auth.localUserId();
    const username = this.auth.username();
    if (!id || !username || this.submitting() || !this.valid()) {
      return;
    }

    this.submitting.set(true);
    this.error.set(null);
    const password = this.newPassword();

    try {
      await firstValueFrom(
        this.api.put<void>(`/local-users/${id}/password`, {
          currentPassword: this.currentPassword(),
          newPassword: password,
        }),
      );
      // The must-change flag lives in the token's claims, so clearing it in the database is not
      // enough — the console has to be holding a token minted after the change. Signing in again
      // is the whole of that: one request, and the new token carries the cleared flag.
      await this.auth.loginLocal(username, password);
    } catch (failure) {
      this.error.set((failure as Problem)?.detail ?? 'Could not change the password.');
      this.submitting.set(false);
    }
  }

  signOut(): void {
    this.auth.logout();
  }
}
