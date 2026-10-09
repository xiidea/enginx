/**
 * Runtime configuration.
 *
 * Read from a global the container's entrypoint can rewrite, so one built image works in several
 * environments. Baking the issuer or API base into the bundle would mean rebuilding to move it.
 */
declare global {
  interface Window {
    __enginx?: Partial<typeof defaults>;
  }
}

const defaults = {
  apiBase: 'http://localhost:8080/api/v1',
  oidcAuthority: 'http://localhost:8081/realms/enginx',
  oidcClientId: 'enginx-frontend',
  // Written by the image's entrypoint from the release it was built for; "dev" for `ng serve`.
  version: 'dev',
};

export const environment = { ...defaults, ...(window.__enginx ?? {}) };
