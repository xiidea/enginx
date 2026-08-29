import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideAuth } from 'angular-auth-oidc-client';
import { App } from './app';
import { authConfig } from './core/auth/auth.config';

describe('App shell', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting(), provideAuth(authConfig)],
    }).compileComponents();
  });

  it('shows the sign-in prompt until the user is authenticated', () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();

    // The shell is not a security boundary: the API refuses unauthenticated requests regardless.
    // This only checks that an anonymous visitor is not shown an empty console full of errors.
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Sign in');
  });
});
