import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideAuth } from 'angular-auth-oidc-client';
import { App } from './app';
import { authConfig } from './core/auth/auth.config';
import { AuthService } from './core/auth/auth.service';

describe('App shell', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting(), provideAuth(authConfig)],
    }).compileComponents();
  });

  function render(): string {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  /**
   * The shell is not a security boundary: the API refuses unauthenticated requests regardless.
   * This only checks that an anonymous visitor is not shown an empty console full of errors.
   */
  it('offers the methods the server reports, and only those', () => {
    TestBed.inject(AuthService).methods.set({ localEnabled: true, oidcEnabled: false });

    const text = render();

    expect(text).toContain('Username');
    expect(text).toContain('Sign in');
    // Offering a redirect to a provider that is switched off produces a login that always fails,
    // with nothing on screen to explain why.
    expect(text).not.toContain('organisation account');
  });

  it('offers the provider when local accounts are switched off', () => {
    TestBed.inject(AuthService).methods.set({ localEnabled: false, oidcEnabled: true });

    const text = render();

    expect(text).toContain('organisation account');
    expect(text).not.toContain('Username');
  });

  it('says so when the server has offered nothing', () => {
    const text = render();

    expect(text).toContain('No sign-in method is available');
  });
});
