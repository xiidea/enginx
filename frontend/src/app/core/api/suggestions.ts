import { map } from 'rxjs';
import { SuggestionSource } from '../../shared/typeahead';
import { GroupsApi, PermissionsApi, SitesApi } from './resources';

/**
 * The sources a typeahead can be pointed at.
 *
 * Each maps a paged API response down to the small shape the menu renders. Keeping the mapping
 * here rather than in the typeahead is what lets one component serve both: it never learns what a
 * site or a person is.
 */

/** People who have signed in, for addressing a permission grant. */
export function peopleSource(api: PermissionsApi): SuggestionSource {
  return (search, page, size) =>
    api.users({ search, page, size }).pipe(
      map((result) => ({
        ...result,
        content: result.content.map((user) => ({
          ref: user.subjectRef,
          label: user.displayName,
          secondary: user.username,
          departed: !user.present,
        })),
      })),
    );
}

/**
 * Proxy sites, by domain.
 *
 * Sorted by domain rather than by relevance: the caller is usually looking for a domain they
 * already know, and a stable order makes the second page mean something.
 */
export function siteSource(api: SitesApi): SuggestionSource {
  return (search, page, size) =>
    api.list({ search, page, size, sort: ['DOMAIN,asc'] }).pipe(
      map((result) => ({
        ...result,
        content: result.content.map((site) => ({
          ref: site.id,
          label: site.domain,
          secondary: site.name === site.domain ? null : site.name,
        })),
      })),
    );
}

/** Domain groups, by path. The set is small but hierarchical, so the path is the useful label. */
export function groupSource(api: GroupsApi): SuggestionSource {
  return (search, page, size) =>
    api.list().pipe(
      map((groups) => {
        const matching = groups.filter(
          (group) =>
            !search
            || group.path.toLowerCase().includes(search.toLowerCase())
            || group.name.toLowerCase().includes(search.toLowerCase()),
        );
        // Filtered and paged in the browser, unlike the others: the group tree is bounded by how
        // many groups an organisation chooses to create, it is already fetched whole to render
        // the tree, and a server-side search would be a second contract for no benefit.
        const from = page * size;
        return {
          content: matching.slice(from, from + size).map((group) => ({
            ref: group.id,
            label: group.path,
            secondary: group.name === group.path ? null : group.name,
          })),
          page,
          size,
          totalElements: matching.length,
          totalPages: Math.ceil(matching.length / size),
        };
      }),
    );
}
