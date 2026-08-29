import { LogLevel, PassedInitialConfig } from 'angular-auth-oidc-client';
import { environment } from '../../../environments/environment';

/**
 * Authorization code flow with PKCE. No client secret, because a public client cannot keep one.
 *
 * Silent renew is on so a session outlives the short access-token lifetime the realm sets — five
 * minutes, chosen so that revoking a group membership takes effect quickly. Without renewal the
 * console would bounce the user to the login page every five minutes.
 */
export const authConfig: PassedInitialConfig = {
  config: {
    authority: environment.oidcAuthority,
    redirectUrl: window.location.origin,
    postLogoutRedirectUri: window.location.origin,
    clientId: environment.oidcClientId,
    scope: 'openid profile email',
    responseType: 'code',
    silentRenew: true,
    useRefreshToken: true,
    renewTimeBeforeTokenExpiresInSeconds: 30,
    // Only the management API receives the token. Attaching it to any other origin would leak it.
    secureRoutes: [environment.apiBase],
    logLevel: LogLevel.Warn,
  },
};
