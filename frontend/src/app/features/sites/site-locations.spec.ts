import { hasOwnSettings, isRoot, locationsToSave, summariseLocation } from './site-form';

describe('site locations', () => {
  it('saves nothing for the root alone, so the site keeps / only', () => {
    expect(locationsToSave([])).toEqual([]);
    expect(locationsToSave([{ pathPattern: '  ', matchType: 'PREFIX' }])).toEqual([]);
  });

  it('always puts the root first when there are other paths, so every request matches one', () => {
    expect(
      locationsToSave([
        { pathPattern: ' /api ', matchType: 'PREFIX' },
        { pathPattern: '/health', matchType: 'EXACT' },
      ]),
    ).toEqual([
      { pathPattern: '/', matchType: 'PREFIX' },
      { pathPattern: '/api', matchType: 'PREFIX' },
      { pathPattern: '/health', matchType: 'EXACT' },
    ]);
  });

  it('does not send the root twice when it is typed in', () => {
    expect(
      locationsToSave([
        { pathPattern: '/', matchType: 'PREFIX' },
        { pathPattern: '/api', matchType: 'PREFIX' },
      ]),
    ).toEqual([
      { pathPattern: '/', matchType: 'PREFIX' },
      { pathPattern: '/api', matchType: 'PREFIX' },
    ]);
  });

  it('treats an exact / as a path of its own, not the root', () => {
    expect(isRoot({ pathPattern: '/', matchType: 'EXACT' })).toBe(false);
    expect(locationsToSave([{ pathPattern: '/', matchType: 'EXACT' }])).toEqual([
      { pathPattern: '/', matchType: 'PREFIX' },
      { pathPattern: '/', matchType: 'EXACT' },
    ]);
  });

  it('sends only what a location sets of its own, so the rest stays the site\'s', () => {
    const [, api] = locationsToSave([
      {
        pathPattern: '/api/',
        matchType: 'PREFIX',
        open: true,
        publicAccess: false,
        upstreams: [
          { scheme: 'http', host: ' 10.0.0.30 ', port: 9000 },
          { scheme: 'http', host: '', port: 8080 },
        ],
        loadBalancingMethod: 'LEAST_CONN',
        readTimeoutSeconds: 300,
        connectTimeoutSeconds: null as unknown as undefined,
        requestHeaders: [
          { name: 'X-Service', value: 'api' },
          { name: ' ', value: 'dropped' },
        ],
      },
    ]);
    expect(api).toEqual({
      pathPattern: '/api/',
      matchType: 'PREFIX',
      upstreams: [{ scheme: 'http', host: '10.0.0.30', port: 9000 }],
      loadBalancingMethod: 'LEAST_CONN',
      readTimeoutSeconds: 300,
      requestHeaders: [{ name: 'X-Service', value: 'api' }],
    });
  });

  it('drops balancing without a pool of its own, and keeps public only when set', () => {
    const [, health] = locationsToSave([
      { pathPattern: '/health', matchType: 'EXACT', publicAccess: true, loadBalancingMethod: 'IP_HASH', upstreams: [] },
    ]);
    expect(health).toEqual({ pathPattern: '/health', matchType: 'EXACT', publicAccess: true });
  });

  it('sends the root back as it was loaded', () => {
    const root = { pathPattern: '/', matchType: 'PREFIX', readTimeoutSeconds: 120 };
    expect(locationsToSave([{ pathPattern: '/api', matchType: 'PREFIX' }], root)[0]).toBe(root);
  });

  it('says in a few words what a location changes', () => {
    expect(hasOwnSettings({ pathPattern: '/api', matchType: 'PREFIX' })).toBe(false);
    expect(
      summariseLocation({
        pathPattern: '/api',
        matchType: 'PREFIX',
        publicAccess: true,
        upstreams: [{ scheme: 'http', host: 'api', port: 80 }],
        maxBodySizeBytes: 52428800,
        websocketEnabled: false,
      }),
    ).toBe('public · own upstreams (1) · body 50 MB · no WebSocket');
  });
});
