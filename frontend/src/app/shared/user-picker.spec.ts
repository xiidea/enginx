import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { environment } from '../../environments/environment';
import { UserPicker } from './user-picker';

function page(content: { username: string; present?: boolean }[], total: number, index = 0) {
  return {
    content: content.map((u) => ({
      subjectType: 'USER',
      subjectRef: `local:${u.username}`,
      displayName: u.username,
      username: u.username,
      present: u.present ?? true,
    })),
    page: index,
    size: 20,
    totalElements: total,
    totalPages: Math.ceil(total / 20),
  };
}

describe('UserPicker', () => {
  let fixture: ComponentFixture<UserPicker>;
  let picker: UserPicker;
  let http: HttpTestingController;

  beforeEach(() => {
    vi.useFakeTimers();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    fixture = TestBed.createComponent(UserPicker);
    picker = fixture.componentInstance;
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  function expectSearch(search: string, index = 0) {
    const match = http.match(
      (r) =>
        r.url === `${environment.apiBase}/users`
        && r.params.get('search') === (search === '' ? null : search)
        && (r.params.get('page') ?? '0') === String(index),
    );
    expect(match.length).toBe(1);
    return match[0];
  }

  it('asks the server for the typed search rather than filtering in the browser', () => {
    picker.onInput('ada');
    vi.advanceTimersByTime(300);

    expectSearch('ada').flush(page([{ username: 'ada' }], 1));

    expect(picker.results().map((u) => u.username)).toEqual(['ada']);
  });

  /**
   * Without debouncing, every keystroke is a query. The point is not politeness to the server:
   * it is that four in-flight responses can land in any order.
   */
  it('issues one request for a burst of typing', () => {
    picker.onInput('a');
    vi.advanceTimersByTime(100);
    picker.onInput('ad');
    vi.advanceTimersByTime(100);
    picker.onInput('ada');
    vi.advanceTimersByTime(300);

    expectSearch('ada').flush(page([{ username: 'ada' }], 1));
    http.verify();
  });

  /**
   * The failure this guards is specific: an early, slower request landing after a later one and
   * replacing the results with those for text the user has already moved on from.
   */
  it('ignores a response that arrives after a newer request', () => {
    picker.onInput('a');
    vi.advanceTimersByTime(300);
    const first = expectSearch('a');

    picker.onInput('ada');
    vi.advanceTimersByTime(300);
    const second = expectSearch('ada');

    second.flush(page([{ username: 'ada' }], 1));
    first.flush(page([{ username: 'stale' }, { username: 'other' }], 2));

    expect(picker.results().map((u) => u.username)).toEqual(['ada']);
  });

  it('appends the next page instead of replacing what is shown', () => {
    picker.onFocus();
    expectSearch('').flush(page([{ username: 'a' }, { username: 'b' }], 3));
    expect(picker.hasMore()).toBe(true);

    picker.loadMore();
    expectSearch('', 1).flush(page([{ username: 'c' }], 3, 1));

    expect(picker.results().map((u) => u.username)).toEqual(['a', 'b', 'c']);
    expect(picker.hasMore()).toBe(false);
  });

  it('reports the selection to the parent and keeps the chosen person', () => {
    const chosen: string[] = [];
    picker.selected.subscribe((ref) => chosen.push(ref));

    picker.onFocus();
    expectSearch('').flush(page([{ username: 'ada' }], 1));
    picker.choose(picker.results()[0]);

    expect(chosen).toEqual(['local:ada']);
    expect(picker.query()).toBe('ada');
    expect(picker.open()).toBe(false);
  });

  it('surfaces a departed subject rather than hiding it', () => {
    picker.onFocus();
    expectSearch('').flush(page([{ username: 'ghost', present: false }], 1));

    expect(picker.results()[0].present).toBe(false);
  });

  it('reports a failure instead of showing an empty directory', () => {
    picker.onFocus();
    expectSearch('').error(new ProgressEvent('network'));

    expect(picker.failed()).toBe(true);
    expect(picker.loading()).toBe(false);
  });
});
