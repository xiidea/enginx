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
import { Subject } from '../core/api/models';
import { PermissionsApi } from '../core/api/resources';

/**
 * Picks a person from the directory of everyone who has signed in.
 *
 * A plain `<select>` was fine while that list was short. It is not a list the platform controls:
 * it gains a row for every person who ever signs in, so on a real directory the select becomes a
 * few thousand options that all arrive before the page is usable. This asks the server instead,
 * a page at a time, filtered by what has been typed.
 */
@Component({
  selector: 'app-user-picker',
  standalone: true,
  templateUrl: './user-picker.html',
  styleUrl: './user-picker.css',
  host: {
    '(document:click)': 'onDocumentClick($event)',
    '(keydown.escape)': 'close()',
  },
})
export class UserPicker implements OnDestroy {
  /** Long enough that typing a name is one request, short enough to feel immediate. */
  private static readonly DEBOUNCE_MS = 250;
  private static readonly PAGE_SIZE = 20;

  private readonly api = inject(PermissionsApi);
  private readonly host = inject(ElementRef<HTMLElement>);

  readonly inputId = input('user-picker');
  /** The selected subject ref, so the parent can drive this from its own state. */
  readonly value = input<string>('');
  readonly selected = output<string>();

  private readonly field = viewChild<ElementRef<HTMLInputElement>>('field');

  readonly query = signal('');
  readonly results = signal<Subject[]>([]);
  readonly open = signal(false);
  readonly loading = signal(false);
  readonly failed = signal(false);
  readonly highlighted = signal(-1);

  /** What was actually chosen, kept apart from the search text so typing does not clear it. */
  readonly chosen = signal<Subject | null>(null);

  private page = 0;
  private total = 0;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private sequence = 0;

  readonly hasMore = computed(() => this.results().length < this.total);

  readonly summary = computed(() => {
    const total = this.total;
    const shown = this.results().length;
    return total > shown ? `${shown} of ${total}` : `${total} ${total === 1 ? 'person' : 'people'}`;
  });

  constructor() {
    // The parent may clear or set the ref on its own, for example after a grant is made.
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
    this.timer = setTimeout(() => this.load(0), UserPicker.DEBOUNCE_MS);
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

  choose(subject: Subject): void {
    this.chosen.set(subject);
    this.query.set(subject.displayName);
    this.selected.emit(subject.subjectRef);
    this.close();
  }

  clear(): void {
    this.chosen.set(null);
    this.query.set('');
    this.selected.emit('');
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
      const next = this.highlighted() + step;
      this.highlighted.set(Math.max(0, Math.min(next, items.length - 1)));
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

    this.api.users({ search: this.query().trim(), page, size: UserPicker.PAGE_SIZE }).subscribe({
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
