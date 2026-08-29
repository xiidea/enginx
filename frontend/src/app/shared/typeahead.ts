import {
  Component,
  ElementRef,
  OnDestroy,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { Observable } from 'rxjs';
import { Page } from '../core/api/models';

/**
 * One row in the menu, reduced to what the menu can render.
 *
 * The caller maps its own type down to this, so the typeahead never learns what a site or a
 * person is — which is what lets one implementation serve both.
 */
export interface Suggestion {
  /** The value reported on selection. */
  ref: string;
  label: string;
  /** Shown to the right, for telling apart two rows that share a label. */
  secondary?: string | null;
  /** Marks a row that still exists in the index but no longer resolves to anything. */
  departed?: boolean;
}

/** Fetches one page of suggestions for the typed text. */
export type SuggestionSource = (search: string, page: number, size: number) => Observable<Page<Suggestion>>;

/**
 * Picks one item from a set too large to put in a `<select>`.
 *
 * A plain select is fine for a list the platform controls the size of. It is the wrong shape for
 * one that grows on its own — every person who ever signs in, every site in the estate — because
 * the whole list arrives before the page is usable and then has to be read by eye. This asks the
 * server instead, a page at a time, filtered by what has been typed.
 *
 * <p>The two things worth knowing about the implementation are both about time: typing is
 * debounced so a burst is one request rather than one per keystroke, and every response is matched
 * against the request that asked for it, so a slow early response cannot replace the results for
 * text the user has already moved on from.
 */
@Component({
  selector: 'app-typeahead',
  standalone: true,
  templateUrl: './typeahead.html',
  styleUrl: './typeahead.css',
  host: {
    '(document:click)': 'onDocumentClick($event)',
    '(keydown.escape)': 'close()',
  },
})
export class Typeahead implements OnDestroy {
  /** Long enough that typing a name is one request, short enough to feel immediate. */
  private static readonly DEBOUNCE_MS = 250;
  private static readonly PAGE_SIZE = 20;

  private readonly host = inject(ElementRef<HTMLElement>);

  readonly source = input.required<SuggestionSource>();
  readonly inputId = input('typeahead');
  readonly placeholder = input('Search');
  /** What to call the things being searched, for the empty and count messages. */
  readonly noun = input<[singular: string, plural: string]>(['result', 'results']);
  /** The selected ref, so the parent can drive this from its own state. */
  readonly value = input<string>('');
  readonly selected = output<Suggestion | null>();

  private readonly field = viewChild<ElementRef<HTMLInputElement>>('field');

  readonly query = signal('');
  readonly results = signal<Suggestion[]>([]);
  readonly open = signal(false);
  readonly loading = signal(false);
  readonly failed = signal(false);
  readonly highlighted = signal(-1);

  /** What was actually chosen, kept apart from the search text so typing does not clear it. */
  readonly chosen = signal<Suggestion | null>(null);

  private page = 0;
  private total = 0;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private sequence = 0;

  readonly hasMore = computed(() => this.results().length < this.total);

  readonly summary = computed(() => {
    const [singular, plural] = this.noun();
    const shown = this.results().length;
    return this.total > shown
      ? `${shown} of ${this.total}`
      : `${this.total} ${this.total === 1 ? singular : plural}`;
  });

  constructor() {
    // The parent may clear the ref on its own, for example after the form it belongs to is
    // submitted. Without this the box would keep showing a selection the parent has forgotten.
    effect(() => {
      if (!this.value() && this.chosen()) {
        this.chosen.set(null);
        this.query.set('');
      }
    });
  }

  ngOnDestroy(): void {
    this.cancelPending();
  }

  onInput(text: string): void {
    this.query.set(text);
    this.open.set(true);
    this.highlighted.set(-1);
    this.cancelPending();
    this.timer = setTimeout(() => this.load(0), Typeahead.DEBOUNCE_MS);
  }

  onFocus(): void {
    this.open.set(true);
    if (!this.results().length) {
      this.load(0);
    }
  }

  /** Loads the next page. The list grows rather than replacing, so scrolling back still works. */
  loadMore(): void {
    if (!this.loading() && this.hasMore()) {
      this.load(this.page + 1);
    }
  }

  onScroll(element: HTMLElement): void {
    const remaining = element.scrollHeight - element.scrollTop - element.clientHeight;
    if (remaining < 48) {
      this.loadMore();
    }
  }

  choose(item: Suggestion): void {
    this.chosen.set(item);
    this.query.set(item.label);
    this.selected.emit(item);
    this.close();
  }

  clear(): void {
    this.chosen.set(null);
    this.query.set('');
    this.selected.emit(null);
    this.results.set([]);
    this.total = 0;
    this.field()?.nativeElement.focus();
    this.load(0);
  }

  onKeydown(event: KeyboardEvent): void {
    const items = this.results();
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      this.open.set(true);
      const step = event.key === 'ArrowDown' ? 1 : -1;
      this.highlighted.set(Math.max(0, Math.min(this.highlighted() + step, items.length - 1)));
      return;
    }
    if (event.key === 'Enter' && this.open()) {
      const item = items[this.highlighted()];
      if (item) {
        event.preventDefault();
        this.choose(item);
      }
    }
  }

  close(): void {
    this.open.set(false);
    this.highlighted.set(-1);
  }

  onDocumentClick(event: MouseEvent): void {
    if (!this.host.nativeElement.contains(event.target as Node)) {
      this.close();
    }
  }

  /**
   * Fetches one page.
   *
   * Responses are matched against the request that asked for them: typing fast enough issues
   * overlapping requests, and without this a slow early one can land last and replace the results
   * for what is now in the box.
   */
  private load(page: number): void {
    const ticket = ++this.sequence;
    this.loading.set(true);
    this.failed.set(false);

    this.source()(this.query().trim(), page, Typeahead.PAGE_SIZE).subscribe({
      next: (result) => {
        if (ticket !== this.sequence) {
          return;
        }
        this.page = result.page;
        this.total = result.totalElements;
        this.results.set(page === 0 ? result.content : [...this.results(), ...result.content]);
        this.loading.set(false);
      },
      error: () => {
        if (ticket !== this.sequence) {
          return;
        }
        this.failed.set(true);
        this.loading.set(false);
      },
    });
  }

  private cancelPending(): void {
    if (this.timer) {
      clearTimeout(this.timer);
      this.timer = null;
    }
  }
}
