import { inject } from '@angular/core';
import { CanActivateFn } from '@angular/router';
import { OidcSecurityService } from 'angular-auth-oidc-client';
import { map } from 'rxjs';

/**
 * Keeps unauthenticated visitors out of the console shell.
 *
 * Not a security control — the API refuses unauthenticated requests regardless. This only avoids
 * rendering a page that would immediately fill with 401s.
 */
export const authGuard: CanActivateFn = () => {
  const oidc = inject(OidcSecurityService);
  return oidc.isAuthenticated$.pipe(map(({ isAuthenticated }) => isAuthenticated));
};
