import { Component, Injectable, inject, signal } from '@angular/core';
import { Problem } from '../core/api/models';

export interface Toast {
  id: number;
  tone: 'ok' | 'danger' | 'info';
  title: string;
  detail?: string;
  fields?: { field: string; message: string }[];
}

/**
 * Transient feedback.
 *
 * Errors are shown as the server described them: an RFC 9457 problem's title and detail are
 * written to be read by a person, and paraphrasing them here would only lose information. Field
 * errors are listed individually so a rejected form says which field, not just that something was
 * wrong.
 */
@Injectable({ providedIn: 'root' })
export class Notifications {
  private next = 1;
  readonly toasts = signal<Toast[]>([]);

  success(title: string, detail?: string): void {
    this.push({ tone: 'ok', title, detail });
  }

  info(title: string, detail?: string): void {
    this.push({ tone: 'info', title, detail });
  }

  problem(problem: Problem): void {
    this.push({
      tone: 'danger',
      title: problem.title ?? 'Request failed',
      detail: problem.detail,
      fields: problem.errors,
    });
  }

  dismiss(id: number): void {
    this.toasts.update((all) => all.filter((toast) => toast.id !== id));
  }

  private push(toast: Omit<Toast, 'id'>): void {
    const id = this.next++;
    this.toasts.update((all) => [...all, { ...toast, id }]);
    // Failures stay until dismissed: an error that vanishes before it is read is worse than none.
    if (toast.tone !== 'danger') {
      setTimeout(() => this.dismiss(id), 4000);
    }
  }
}

@Component({
  selector: 'app-toasts',
  standalone: true,
  template: `
    <div class="toasts" role="status" aria-live="polite">
      @for (toast of notifications.toasts(); track toast.id) {
        <div class="toast" [class]="'tone-' + toast.tone">
          <div class="row-between">
            <strong>{{ toast.title }}</strong>
            <button class="close" type="button" (click)="notifications.dismiss(toast.id)" aria-label="Dismiss">×</button>
          </div>
          @if (toast.detail) {
            <p>{{ toast.detail }}</p>
          }
          @if (toast.fields?.length) {
            <ul>
              @for (field of toast.fields; track field.field) {
                <li><code>{{ field.field }}</code> {{ field.message }}</li>
              }
            </ul>
          }
        </div>
      }
    </div>
  `,
  styles: [
    `
      .toasts {
        position: fixed;
        right: 1rem;
        bottom: 1rem;
        z-index: 50;
        display: flex;
        flex-direction: column;
        gap: 0.5rem;
        max-width: min(28rem, calc(100vw - 2rem));
      }
      .toast {
        padding: 0.7rem 0.85rem;
        border: 1px solid currentColor;
        border-left-width: 3px;
        border-radius: var(--radius-sm);
        background: var(--surface);
        box-shadow: var(--shadow);
        font-size: 0.85rem;
      }
      .toast strong { color: var(--ink); }
      .toast p { margin: 0.3rem 0 0; color: var(--ink-2); }
      .toast ul { margin: 0.4rem 0 0; padding-left: 1.1rem; color: var(--ink-2); }
      .toast code { font-family: var(--f-mono); font-size: 0.8em; }
      .tone-ok { color: var(--ok); }
      .tone-danger { color: var(--danger); }
      .tone-info { color: var(--info); }
      .close {
        border: 0;
        background: none;
        color: var(--ink-3);
        font-size: 1.1rem;
        line-height: 1;
        cursor: pointer;
        padding: 0 0.2rem;
      }
    `,
  ],
})
export class Toasts {
  readonly notifications = inject(Notifications);
}
