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
 */
export const authGuard: CanActivateFn = () => inject(AuthService).authenticated();
