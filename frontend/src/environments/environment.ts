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
};

export const environment = { ...defaults, ...(window.__enginx ?? {}) };
