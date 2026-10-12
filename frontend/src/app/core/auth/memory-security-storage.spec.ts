import { MemorySecurityStorage } from './memory-security-storage';

/** The serialised entry the OIDC library writes under its config id, once signed in. */
const SIGNED_IN = JSON.stringify({
  authWellKnownEndPoints: { tokenEndpoint: 'https://idp.example/token' },
  codeVerifier: 'verifier',
  authStateControl: 'state',
  authNonce: 'nonce',
  authzData: 'access-token',
  authnResult: { id_token: 'id-token', refresh_token: 'refresh-token' },
  reusable_refresh_token: 'refresh-token',
  userData: { sub: 'user-1' },
});

describe('MemorySecurityStorage', () => {
  beforeEach(() => sessionStorage.clear());

  it('hands back everything it was given, from memory', () => {
    const storage = new MemorySecurityStorage();
    storage.write('0', SIGNED_IN);

    expect(storage.read('0')).toBe(SIGNED_IN);
  });

  it('persists only what the code-flow redirect needs, never a token', () => {
    new MemorySecurityStorage().write('0', SIGNED_IN);

    const persisted = sessionStorage.getItem('0')!;
    expect(JSON.parse(persisted)).toEqual({
      authWellKnownEndPoints: { tokenEndpoint: 'https://idp.example/token' },
      codeVerifier: 'verifier',
      authStateControl: 'state',
      authNonce: 'nonce',
    });
    expect(persisted).not.toMatch(/access-token|id-token|refresh-token|user-1/);
  });

  it('carries the redirect state across a page load', () => {
    new MemorySecurityStorage().write(
      '0',
      JSON.stringify({ codeVerifier: 'verifier', authStateControl: 'state' }),
    );

    const afterRedirect = new MemorySecurityStorage();
    expect(JSON.parse(afterRedirect.read('0')!)).toEqual({
      codeVerifier: 'verifier',
      authStateControl: 'state',
    });
  });

  it('never reads a token an earlier build left in sessionStorage', () => {
    sessionStorage.setItem('0', SIGNED_IN);

    expect(new MemorySecurityStorage().read('0')).not.toMatch(
      /access-token|id-token|refresh-token|user-1/,
    );
  });

  it('removes the persisted state along with the in-memory one', () => {
    const storage = new MemorySecurityStorage();
    storage.write('0', SIGNED_IN);
    storage.remove('0');

    expect(storage.read('0')).toBeNull();
    expect(sessionStorage.getItem('0')).toBeNull();
  });
});
