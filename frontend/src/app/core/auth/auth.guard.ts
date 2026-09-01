import { inject } from '@angular/core';
import { CanActivateFn } from '@angular/router';
import { AuthService } from './auth.service';

/**
 * Keeps unauthenticated visitors out of the console shell.
 *
 * Not a security control — the API refuses unauthenticated requests regardless. This only avoids
 * rendering a page that would immediately fill with 401s.
 *
 * Reads the console's own session rather than the OIDC library's, because a local session is
 * invisible to that library and would otherwise be turned away from every route.
 *
 * When it turns a visitor away, it records where they were headed. A local sign-in cancels the
 * initial navigation (there is no session yet), so without this the router would have nowhere to
 * resume to and `loginLocal` would land on the default page even for a deep link.
 */
export const authGuard: CanActivateFn = (_route, state) => {
  const auth = inject(AuthService);
  if (auth.authenticated()) {
    return true;
  }
  auth.intendedUrl = state.url;
  return false;
};
