import { Component, computed, input } from '@angular/core';

type Tone = 'ok' | 'warn' | 'danger' | 'info' | 'muted';

/**
 * A status, encoded in shape as well as colour.
 *
 * The dot carries the same information as the fill, so the state is still legible to someone who
 * cannot distinguish the hues — and legible at a glance in a dense table, which is the whole point
 * of a status column.
 */
@Component({
  selector: 'app-status-pill',
  standalone: true,
  template: `
    <span class="pill" [class]="'tone-' + tone()" [title]="title() || label()">
      <span class="dot" aria-hidden="true"></span>{{ label() }}
    </span>
  `,
  styles: [
    `
      .pill {
        display: inline-flex;
        align-items: center;
        gap: 0.4rem;
        padding: 0.12rem 0.5rem 0.12rem 0.42rem;
        border: 1px solid currentColor;
        border-radius: 999px;
        font-family: var(--f-mono);
        font-size: 0.7rem;
        font-weight: 500;
        letter-spacing: 0.04em;
        white-space: nowrap;
      }
      .dot {
        width: 0.42rem;
        height: 0.42rem;
        border-radius: 50%;
        background: currentColor;
        flex: none;
      }
      .tone-ok { color: var(--ok); background: var(--ok-wash); }
      .tone-warn { color: var(--warn); background: var(--warn-wash); }
      .tone-danger { color: var(--danger); background: var(--danger-wash); }
      .tone-info { color: var(--info); background: var(--info-wash); }
      .tone-muted { color: var(--ink-3); background: var(--muted-wash); }
    `,
  ],
})
export class StatusPill {
  readonly status = input.required<string | null>();
  readonly title = input<string | null>(null);

  readonly label = computed(() => this.status() ?? 'UNKNOWN');

  readonly tone = computed<Tone>(() => {
    switch (this.status()) {
      case 'ACTIVE':
      case 'SUCCESS':
      case 'VALID':
      case 'ONLINE':
        return 'ok';
      case 'PENDING':
      case 'IN_PROGRESS':
      case 'EXPIRING_SOON':
      case 'DEGRADED':
        return 'warn';
      case 'ERROR':
      case 'FAILED':
      case 'EXPIRED':
      case 'REVOKED':
      case 'OFFLINE':
        return 'danger';
      case 'DISABLED':
      case 'CANCELLED':
      case 'UNKNOWN':
        return 'muted';
      default:
        return 'info';
    }
  });
}
