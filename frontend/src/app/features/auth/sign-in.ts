import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AuthService } from '../../core/auth/auth.service';

/**
 * The sign-in screen.
 *
 * Which methods appear is the server's decision, read from `/auth/methods`. Offering a form for a
 * method that is switched off would produce a login that always fails, with nothing on screen to
 * explain why.
 */
@Component({
  selector: 'app-sign-in',
  standalone: true,
  imports: [FormsModule],
  templateUrl: './sign-in.html',
  styleUrl: './sign-in.css',
})
export class SignIn {
  readonly auth = inject(AuthService);

  readonly username = signal('');
  readonly password = signal('');
  readonly submitting = signal(false);
  readonly error = signal<string | null>(null);

  async submit(): Promise<void> {
    if (this.submitting()) {
      return;
    }
    this.submitting.set(true);
    this.error.set(null);
    try {
      await this.auth.loginLocal(this.username().trim(), this.password());
      // loginLocal performs the redirect: it resumes a remembered deep link or lands on the
      // dashboard, so there is nothing to navigate to from here.
    } catch (failure) {
      this.error.set(describe(failure));
      this.password.set('');
    } finally {
      this.submitting.set(false);
    }
  }
}

/**
 * Turns a failed sign-in into something worth reading.
 *
 * The 401 text is deliberately the server's single message for every rejection — wrong password,
 * no such account, account disabled — because telling them apart turns the form into an account
 * enumerator. The other cases are distinguished, because they are the user's problem to act on.
 */
function describe(failure: unknown): string {
  if (!(failure instanceof HttpErrorResponse)) {
    return 'Sign-in failed.';
  }
  if (failure.status === 401) {
    return 'Incorrect username or password.';
  }
  if (failure.status === 429) {
    return 'Too many sign-in attempts. Wait a minute and try again.';
  }
  if (failure.status === 0) {
    return 'Cannot reach the server.';
  }
  return failure.error?.detail ?? 'Sign-in failed.';
}
