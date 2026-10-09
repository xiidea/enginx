import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { OidcSecurityService } from 'angular-auth-oidc-client';
import { firstValueFrom } from 'rxjs';
import { environment } from '../../../environments/environment';
import { LocalSession, claimsOf } from './local-session';

export type Role = 'SUPER_ADMIN' | 'ADMIN' | 'OPERATOR' | 'READ_ONLY';

/** Which sign-in methods the server has switched on. */
export interface AuthMethods {
  localEnabled: boolean;
  oidcEnabled: boolean;
  /** The management server's release; absent when it was built without build-info. */
  serverVersion?: string;
}

/**
 * Who is signed in, and what the console should offer them.
 *
 * Everything here is advisory. The server re-evaluates every request against the caller's grants,
 * so these signals decide what to *show*, never what is allowed. Hiding an action the server
 * would refuse is a courtesy; relying on that hiding for safety would not be.
 *
 * <p>Two providers can be live at once, and the console does not care which one signed the caller
 * in: both issue a token carrying the same claims, so everything below reads one shape.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly oidc = inject(OidcSecurityService);
  private readonly local = inject(LocalSession);
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);

  /**
   * Where a turned-away visitor was headed, set by the route guard. A local sign-in resumes here
   * so a deep link survives the round trip through the sign-in screen; null means the dashboard.
   */
  intendedUrl: string | null = null;

  readonly authenticated = signal(false);
  readonly username = signal<string | null>(null);
  readonly subject = signal<string | null>(null);
  readonly roles = signal<ReadonlySet<Role>>(new Set());
  readonly groups = signal<readonly string[]>([]);

  /**
   * What the sign-in page should offer.
   *
   * Defaults to neither until the server has answered, so the page never flashes a form for a
   * method that turns out to be switched off.
   */
  readonly methods = signal<AuthMethods>({ localEnabled: false, oidcEnabled: false });

  /** Which provider established the current session, once there is one. */
  readonly provider = signal<'local' | 'oidc' | null>(null);

  /** The signed-in local account has not yet replaced the password it was given. */
  readonly mustChangePassword = this.local.mustChangePassword;

  /**
   * The local account's own identifier, for the endpoints that are addressed by it.
   *
   * Null for a federated session, whose subject belongs to the identity provider and names no row
   * in the local user table.
   */
  readonly localUserId = computed(() => {
    const subject = this.subject();
    return subject?.startsWith('local:') ? subject.slice('local:'.length) : null;
  });

  readonly initials = computed(() => {
    const name = this.username();
    return name ? name.slice(0, 2).toUpperCase() : '??';
  });

  /**
   * Realm roles set a floor and a ceiling; the per-domain detail comes from the server. These
   * only gate the parts of the console that are globally scoped, such as registering an NGINX
   * instance.
   */
  readonly isSuperAdmin = computed(() => this.roles().has('SUPER_ADMIN'));
  readonly isReadOnly = computed(
    () => this.roles().has('READ_ONLY') && !this.roles().has('ADMIN') && !this.roles().has('SUPER_ADMIN')
      && !this.roles().has('OPERATOR'),
  );

  /**
   * Completes once the session has been established or ruled out.
   *
   * Returned as a promise so it can gate application start-up: the router must not decide whether
   * a route is reachable before this has answered.
   */
  async initialise(): Promise<void> {
    this.methods.set(await this.loadMethods());

    // A stored local token wins, because it is the one this console can check without a network
    // round trip. Only if there is none does the OIDC library get to look for its own session.
    this.local.restore();
    const stored = this.local.token();
    if (stored) {
      this.adopt(stored, 'local');
      return;
    }

    if (!this.methods().oidcEnabled) {
      return;
    }
    await this.establishOidcSession();
  }

  /** Redirects to the identity provider. */
  login(): void {
    this.oidc.authorize();
  }

  /**
   * Signs in against a local account.
   *
   * @throws the HTTP error as-is, so the form can tell 401 (wrong credentials) apart from a
   *         server that is simply unreachable.
   */
  async loginLocal(username: string, password: string): Promise<void> {
    await this.local.login(username, password);
    this.adopt(this.local.token()!, 'local');

    // The router's initial navigation was cancelled by the guard while there was no session, so
    // the shell would otherwise swap to an empty outlet. Navigate now that one exists — resuming
    // a remembered deep link, or the dashboard by default.
    //
    // Except when the account must first replace its password: it is confined to the
    // password-change screen, and PasswordChange re-calls this after the change, when the cleared
    // flag lets this same navigation through.
    if (this.mustChangePassword()) {
      return;
    }
    const target = this.intendedUrl ?? '/dashboard';
    this.intendedUrl = null;
    await this.router.navigateByUrl(target);
  }

  logout(): void {
    const provider = this.provider();
    this.clearSession();
    if (provider === 'oidc') {
      // Ends the session at the provider too, so the next sign-in is a real one rather than a
      // silent redirect straight back in.
      this.oidc.logoff().subscribe();
    }
  }

  /**
   * Asks the server which methods are on.
   *
   * An unreachable server leaves both off rather than guessing: offering a form that cannot work
   * is worse than saying the platform is unavailable.
   */
  private async loadMethods(): Promise<AuthMethods> {
    try {
      return await firstValueFrom(this.http.get<AuthMethods>(`${environment.apiBase}/auth/methods`));
    } catch {
      return { localEnabled: false, oidcEnabled: false };
    }
  }

  private async establishOidcSession(): Promise<void> {
    const settled = firstValueFrom(this.oidc.checkAuth()).then(({ isAuthenticated, accessToken }) => {
      if (isAuthenticated) {
        this.adopt(accessToken, 'oidc');
      }
    });

    // Keep the projection in step with silent renewals, which issue a new token with possibly
    // different group membership.
    this.oidc.getAccessToken().subscribe((token) => {
      if (token) {
        this.adopt(token, 'oidc');
      }
    });

    return settled;
  }

  private clearSession(): void {
    this.local.clear();
    this.authenticated.set(false);
    this.provider.set(null);
    this.subject.set(null);
    this.username.set(null);
    this.roles.set(new Set());
    this.groups.set([]);
  }

  /**
   * Projects a token's claims onto the signals the console renders from.
   *
   * Decoding is display-only: nothing here is trusted, and a forged token would fail signature
   * validation at the server. Roles and groups are read from the access token rather than the id
   * token because the API authorises on exactly these claims.
   */
  private adopt(accessToken: string, provider: 'local' | 'oidc'): void {
    const claims = claimsOf(accessToken);
    if (!claims) {
      return;
    }
    this.authenticated.set(true);
    this.provider.set(provider);
    this.subject.set(claims.sub ?? null);
    this.username.set(claims.preferred_username ?? claims.name ?? null);
    this.groups.set(claims.groups ?? []);

    const known: Role[] = ['SUPER_ADMIN', 'ADMIN', 'OPERATOR', 'READ_ONLY'];
    const roles = new Set<Role>();
    for (const role of claims.realm_access?.roles ?? []) {
      if ((known as string[]).includes(role)) {
        roles.add(role as Role);
      }
    }
    this.roles.set(roles);
  }
}
