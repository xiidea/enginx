import { isRoot, locationsToSave } from './site-form';

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
});
