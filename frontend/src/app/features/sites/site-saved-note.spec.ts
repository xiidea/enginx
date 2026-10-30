import { Site } from '../../core/api/models';
import { savedNote } from './site-form';

describe('saved note', () => {
  const site = { domain: 'app.example.com' } as Site;

  it('says the change is deploying when the save queued a deployment', () => {
    expect(savedNote({ ...site, deploymentId: 'd-1' }, true)).toContain('deploying the change');
  });

  it('says the host keeps the old configuration after saving without deploying', () => {
    expect(savedNote(site, false)).toContain('until the next deployment');
  });

  it('points at the site page when a deploying save needed no deployment', () => {
    expect(savedNote(site, true)).toContain('nothing to deploy now');
  });
});
