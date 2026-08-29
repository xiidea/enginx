import { Component, input } from '@angular/core';

@Component({
  selector: 'app-page-header',
  standalone: true,
  template: `
    <header class="head">
      <div>
        <h1>{{ title() }}</h1>
        @if (subtitle()) {
          <p class="sub">{{ subtitle() }}</p>
        }
      </div>
      <div class="actions"><ng-content /></div>
    </header>
  `,
  styles: [
    `
      .head {
        display: flex;
        align-items: flex-start;
        justify-content: space-between;
        gap: 1rem;
        flex-wrap: wrap;
        margin-bottom: 1.25rem;
      }
      .sub { margin: 0.2rem 0 0; color: var(--ink-3); font-size: 0.87rem; max-width: 62ch; }
      .actions { display: flex; align-items: center; gap: 0.5rem; }
    `,
  ],
})
export class PageHeader {
  readonly title = input.required<string>();
  readonly subtitle = input<string | null>(null);
}

@Component({
  selector: 'app-empty',
  standalone: true,
  template: `
    <div class="empty">
      <p class="title">{{ title() }}</p>
      @if (detail()) {
        <p class="detail">{{ detail() }}</p>
      }
      <ng-content />
    </div>
  `,
  styles: [
    `
      .empty { padding: 2.5rem 1.5rem; text-align: center; }
      .title { margin: 0; font-weight: 500; color: var(--ink-2); }
      .detail { margin: 0.35rem 0 0.9rem; color: var(--ink-3); font-size: 0.85rem; }
    `,
  ],
})
export class EmptyState {
  readonly title = input.required<string>();
  readonly detail = input<string | null>(null);
}
