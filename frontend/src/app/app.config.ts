import {
  ApplicationConfig,
  inject,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
  provideZonelessChangeDetection,
} from '@angular/core';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { authInterceptor, provideAuth } from 'angular-auth-oidc-client';
import { localTokenInterceptor } from './core/auth/local-token.interceptor';
import { routes } from './app.routes';
import { authConfig } from './core/auth/auth.config';
import { AuthService } from './core/auth/auth.service';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideZonelessChangeDetection(),
    // withComponentInputBinding lets route parameters arrive as component inputs, so a detail
    // page declares what it needs instead of reaching into the router for it.
    provideRouter(routes, withComponentInputBinding()),
    // The interceptor attaches the bearer token, and only to the routes named in authConfig.
    provideHttpClient(withInterceptors([localTokenInterceptor, authInterceptor()])),
    provideAuth(authConfig),
    // Authentication resolves before the router navigates.
    //
    // Otherwise the first navigation races checkAuth: the guard samples isAuthenticated$ while it
    // is still false, blocks the route, and the shell renders with an empty outlet — signed in,
    // with nothing on the page. Resolving here means the guard's first answer is the true one.
    provideAppInitializer(() => inject(AuthService).initialise()),
  ],
};
