import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { OidcSecurityService } from 'angular-auth-oidc-client';
import { of } from 'rxjs';
import { environment } from '../../../environments/environment';
import { AuthService } from './auth.service';
import { localTokenInterceptor } from './local-token.interceptor';

/** A token shaped like the one the server issues. Never verified here; only decoded. */
function tokenWith(claims: Record<string, unknown>): string {
  const encode = (value: object) => btoa(JSON.stringify(value)).replace(/=+$/, '');
  return `${encode({ alg: 'HS256' })}.${encode(claims)}.signature-not-checked-here`;
}

const LOCAL_TOKEN = tokenWith({
  iss: 'enginx-local',
  sub: 'local:aaad024a-0287-43f5-82e2-06ef01615afe',
  preferred_username: 'ada',
  exp: Math.floor(Date.now() / 1000) + 3600,
  realm_access: { roles: ['OPERATOR', 'NOT_A_ROLE'] },
  groups: ['/platform/operators'],
});

describe('AuthService', () => {
  let oidc: {
    checkAuth: ReturnType<typeof vi.fn>;
    getAccessToken: ReturnType<typeof vi.fn>;
    authorize: ReturnType<typeof vi.fn>;
    logoff: ReturnType<typeof vi.fn>;
  };
  let router: { navigateByUrl: ReturnType<typeof vi.fn> };
  let http: HttpTestingController;

  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    oidc = {
      checkAuth: vi.fn(() => of({ isAuthenticated: false, accessToken: '' })),
      getAccessToken: vi.fn(() => of('')),
      authorize: vi.fn(),
      logoff: vi.fn(() => of(null)),
    };
    router = { navigateByUrl: vi.fn(() => Promise.resolve(true)) };

    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([localTokenInterceptor])),
        provideHttpClientTesting(),
        { provide: OidcSecurityService, useValue: oidc },
        { provide: Router, useValue: router },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function answerMethods(localEnabled: boolean, oidcEnabled: boolean): void {
    http.expectOne(`${environment.apiBase}/auth/methods`).flush({ localEnabled, oidcEnabled });
  }

  /**
   * The point of the whole feature: a deployment with no identity provider must not have its
   * console reach for one. The library's discovery request would fail against a host that is not
   * running, and the sign-in page would never appear.
   */
  it('does not touch the identity provider when OIDC is switched off', async () => {
    const auth = TestBed.inject(AuthService);
    const started = auth.initialise();
    answerMethods(true, false);
    await started;

    expect(oidc.checkAuth).not.toHaveBeenCalled();
    expect(auth.methods()).toEqual({ localEnabled: true, oidcEnabled: false });
    expect(auth.authenticated()).toBe(false);
  });

  it('establishes an OIDC session when only OIDC is switched on', async () => {
    const auth = TestBed.inject(AuthService);
    const started = auth.initialise();
    answerMethods(false, true);
    await started;

    expect(oidc.checkAuth).toHaveBeenCalled();
  });

  /**
   * The server counts client roles on the console's own client as well as realm roles, so a
   * deployment that keeps its roles on the client alone must not get a console that hides
   * everything. Another client's roles grant nothing on the server, and so nothing here.
   */
  it('reads roles from the realm and from the console client only', async () => {
    const oidcToken = tokenWith({
      sub: 'user-1',
      preferred_username: 'console-admin',
      realm_access: { roles: ['READ_ONLY'] },
      resource_access: {
        [environment.oidcClientId]: { roles: ['SUPER_ADMIN'] },
        'some-other-app': { roles: ['ADMIN'] },
      },
    });
    oidc.checkAuth.mockReturnValue(of({ isAuthenticated: true, accessToken: oidcToken }));

    const auth = TestBed.inject(AuthService);
    const started = auth.initialise();
    answerMethods(false, true);
    await started;

    expect(auth.provider()).toBe('oidc');
    expect([...auth.roles()].sort()).toEqual(['READ_ONLY', 'SUPER_ADMIN']);
    expect(auth.isSuperAdmin()).toBe(true);
  });

  it('projects the claims of a local token onto the session', async () => {
    const auth = TestBed.inject(AuthService);
    const started = auth.initialise();
    answerMethods(true, false);
    await started;

    const signedIn = auth.loginLocal('ada', 'a-perfectly-ordinary-passphrase');
    http.expectOne(`${environment.apiBase}/auth/login`).flush({ accessToken: LOCAL_TOKEN });
    await signedIn;

    expect(auth.authenticated()).toBe(true);
    expect(auth.provider()).toBe('local');
    expect(auth.username()).toBe('ada');
    expect(auth.localUserId()).toBe('aaad024a-0287-43f5-82e2-06ef01615afe');
    // An unrecognised role is dropped rather than carried around as a string nothing can act on.
    expect([...auth.roles()]).toEqual(['OPERATOR']);
  });

  /**
   * The reported bug: the guard cancels the initial navigation while there is no session, so
   * after a local sign-in the shell would swap to an empty outlet. loginLocal must navigate.
   */
  it('redirects to the dashboard after a local sign-in', async () => {
    const auth = TestBed.inject(AuthService);
    const started = auth.initialise();
    answerMethods(true, false);
    await started;

    const signedIn = auth.loginLocal('ada', 'a-perfectly-ordinary-passphrase');
    http.expectOne(`${environment.apiBase}/auth/login`).flush({ accessToken: LOCAL_TOKEN });
    await signedIn;

    expect(router.navigateByUrl).toHaveBeenCalledWith('/dashboard');
  });

  it('resumes the deep link the guard remembered', async () => {
    const auth = TestBed.inject(AuthService);
    const started = auth.initialise();
    answerMethods(true, false);
    await started;

    auth.intendedUrl = '/sites/123';
    const signedIn = auth.loginLocal('ada', 'a-perfectly-ordinary-passphrase');
    http.expectOne(`${environment.apiBase}/auth/login`).flush({ accessToken: LOCAL_TOKEN });
    await signedIn;

    expect(router.navigateByUrl).toHaveBeenCalledWith('/sites/123');
    // Consumed, so a later sign-in does not land on a stale destination.
    expect(auth.intendedUrl).toBeNull();
  });

  /**
   * An account that must replace its password is confined to the password-change screen; sending
   * it to a route behind that screen would be pointless. The redirect happens on the second
   * loginLocal PasswordChange makes once the flag is cleared.
   */
  it('does not redirect when the account must change its password', async () => {
    const auth = TestBed.inject(AuthService);
    const started = auth.initialise();
    answerMethods(true, false);
    await started;

    const mustChange = tokenWith({
      iss: 'enginx-local',
      sub: 'local:aaad024a-0287-43f5-82e2-06ef01615afe',
      preferred_username: 'ada',
      exp: Math.floor(Date.now() / 1000) + 3600,
      must_change_password: true,
    });
    const signedIn = auth.loginLocal('ada', 'the-temporary-passphrase');
    http.expectOne(`${environment.apiBase}/auth/login`).flush({ accessToken: mustChange });
    await signedIn;

    expect(auth.mustChangePassword()).toBe(true);
    expect(router.navigateByUrl).not.toHaveBeenCalled();
  });

  /**
   * A bearer token sent anywhere but the API is a credential handed to a third party, on every
   * request. The check is worth a test because nothing else would notice it being removed.
   */
  it('attaches the local token to the API and to nothing else', async () => {
    const auth = TestBed.inject(AuthService);
    const started = auth.initialise();
    answerMethods(true, false);
    await started;

    const signedIn = auth.loginLocal('ada', 'a-perfectly-ordinary-passphrase');
    http.expectOne(`${environment.apiBase}/auth/login`).flush({ accessToken: LOCAL_TOKEN });
    await signedIn;

    const client = TestBed.inject(HttpClient);
    client.get(`${environment.apiBase}/proxy-sites`).subscribe();
    expect(http.expectOne(`${environment.apiBase}/proxy-sites`).request.headers.get('Authorization'))
      .toBe(`Bearer ${LOCAL_TOKEN}`);

    client.get('https://analytics.example.com/beacon').subscribe();
    expect(http.expectOne('https://analytics.example.com/beacon').request.headers.has('Authorization'))
      .toBe(false);
  });

  /** A reload, or a new tab, must not ask a local user for their password again. */
  it('restores a stored local session, and discards an expired one', async () => {
    localStorage.setItem('enginx.local.token', LOCAL_TOKEN);
    const auth = TestBed.inject(AuthService);
    const started = auth.initialise();
    answerMethods(true, false);
    await started;

    expect(auth.authenticated()).toBe(true);
    expect(auth.username()).toBe('ada');

    localStorage.setItem('enginx.local.token', tokenWith({ sub: 'local:x', exp: 1 }));
    TestBed.resetTestingModule();
  });

  describe('an OIDC session after a reload', () => {
    const OIDC_TOKEN = tokenWith({ sub: 'user-1', preferred_username: 'console-admin' });

    /** Resolves to whether the promise settled within a tick, without waiting on one that never does. */
    async function settles(promise: Promise<void>): Promise<boolean> {
      return Promise.race([
        promise.then(() => true),
        new Promise<boolean>((r) => setTimeout(() => r(false), 20)),
      ]);
    }

    it('marks the browser as signed in, without storing the token', async () => {
      oidc.checkAuth.mockReturnValue(of({ isAuthenticated: true, accessToken: OIDC_TOKEN }));
      const auth = TestBed.inject(AuthService);
      const started = auth.initialise();
      answerMethods(false, true);
      await started;

      expect(auth.provider()).toBe('oidc');
      expect(localStorage.getItem('enginx.oidc.signedIn')).toBe('1');
      expect(JSON.stringify({ ...localStorage, ...sessionStorage })).not.toContain(OIDC_TOKEN);
    });

    it('asks the provider for its session silently when the browser was signed in', async () => {
      localStorage.setItem('enginx.oidc.signedIn', '1');
      const auth = TestBed.inject(AuthService);
      const started = auth.initialise();
      answerMethods(false, true);

      // The page is navigating away, so start-up must not settle and render the sign-in screen.
      expect(await settles(started)).toBe(false);
      expect(oidc.authorize).toHaveBeenCalledWith(undefined, { customParams: { prompt: 'none' } });
      expect(sessionStorage.getItem('enginx.oidc.silentAttempt')).toBe('1');
    });

    it('does not bounce through the provider for a browser that never signed in with it', async () => {
      const auth = TestBed.inject(AuthService);
      const started = auth.initialise();
      answerMethods(true, true);
      await started;

      expect(oidc.authorize).not.toHaveBeenCalled();
      expect(auth.authenticated()).toBe(false);
    });

    /** login_required comes back when the provider's session is gone. One try, then the sign-in page. */
    it('falls back to the sign-in page, once, when the provider has no session', async () => {
      localStorage.setItem('enginx.oidc.signedIn', '1');
      sessionStorage.setItem('enginx.oidc.silentAttempt', '1');
      const auth = TestBed.inject(AuthService);
      const started = auth.initialise();
      answerMethods(false, true);
      await started;

      expect(oidc.authorize).not.toHaveBeenCalled();
      expect(auth.authenticated()).toBe(false);
      expect(localStorage.getItem('enginx.oidc.signedIn')).toBeNull();
      expect(sessionStorage.getItem('enginx.oidc.silentAttempt')).toBeNull();
    });

    it('forgets the browser was signed in on sign-out', async () => {
      oidc.checkAuth.mockReturnValue(of({ isAuthenticated: true, accessToken: OIDC_TOKEN }));
      const auth = TestBed.inject(AuthService);
      const started = auth.initialise();
      answerMethods(false, true);
      await started;

      auth.logout();

      expect(oidc.logoff).toHaveBeenCalled();
      expect(localStorage.getItem('enginx.oidc.signedIn')).toBeNull();
    });
  });

  it('signs out of a local session without redirecting anywhere', async () => {
    const auth = TestBed.inject(AuthService);
    const started = auth.initialise();
    answerMethods(true, false);
    await started;

    const signedIn = auth.loginLocal('ada', 'a-perfectly-ordinary-passphrase');
    http.expectOne(`${environment.apiBase}/auth/login`).flush({ accessToken: LOCAL_TOKEN });
    await signedIn;

    auth.logout();

    expect(auth.authenticated()).toBe(false);
    expect(localStorage.getItem('enginx.local.token')).toBeNull();
  });
});
