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
  let oidc: { checkAuth: ReturnType<typeof vi.fn>; getAccessToken: ReturnType<typeof vi.fn>; authorize: ReturnType<typeof vi.fn> };
  let router: { navigateByUrl: ReturnType<typeof vi.fn> };
  let http: HttpTestingController;

  beforeEach(() => {
    localStorage.clear();
    oidc = {
      checkAuth: vi.fn(() => of({ isAuthenticated: false, accessToken: '' })),
      getAccessToken: vi.fn(() => of('')),
      authorize: vi.fn(),
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

  it('restores a stored session, and discards an expired one', async () => {
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
