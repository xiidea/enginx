import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { environment } from '../../../environments/environment';
import { LocalSession } from './local-session';

/**
 * Attaches a locally issued token to management API requests.
 *
 * Runs alongside the OIDC library's own interceptor rather than replacing it: only one of the two
 * ever holds a token, so whichever provider signed the caller in is the one that authorises the
 * request.
 *
 * <p>The origin check is the point of the whole function. A bearer token sent to any other host —
 * a CDN, an avatar service, an analytics beacon — is a credential handed to a third party, and it
 * would be sent on every single request. The API base is the only destination that gets it.
 */
export const localTokenInterceptor: HttpInterceptorFn = (request, next) => {
  const token = inject(LocalSession).token();
  if (!token || !request.url.startsWith(environment.apiBase)) {
    return next(request);
  }
  return next(request.clone({ setHeaders: { Authorization: `Bearer ${token}` } }));
};
