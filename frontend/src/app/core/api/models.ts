/**
 * The API's data model.
 *
 * Types that correspond to a server DTO are aliases of the generated schema rather than
 * hand-written copies. That is the whole point: a field renamed or removed on the server becomes a
 * TypeScript error here, instead of a value that is quietly `undefined` at runtime and noticed by
 * a user. Regenerate with `npm run generate:api` after refreshing `docs/api/openapi.json`.
 *
 * The aliases also give the generated names a vocabulary the console actually uses: the server
 * calls it `ProxySiteResponse` because it is a response; here it is simply a `Site`.
 */
import type { components } from './schema';

type Schemas = components['schemas'];

/**
 * Bridges one convention difference, and only that.
 *
 * The generated schema marks a nullable field optional (`x?: string`) because that is how OpenAPI
 * says "may be absent". The console has always said `x: string | null`. Both mean the same thing —
 * and for this server the console's version is the more accurate of the two, since Jackson
 * serialises every record component, so the key is always present and it is the value that is
 * null. Converting here keeps the whole console on one convention instead of scattering `?? null`
 * across every template.
 *
 * Required fields are untouched: they stay required and non-null, which is the guarantee the
 * `requiredProperties` annotations on the server exist to publish.
 */
type Wire<T> = {
  [K in keyof T]-?: undefined extends T[K] ? Exclude<T[K], undefined> | null : T[K];
};

/**
 * Wire types for the management API.
 *
 * Hand-written rather than generated. The surface is small enough that explicit types are easier
 * to read than a generator's output, and they document what the console actually consumes.
 * Generating from the OpenAPI document in CI is the better long-term answer once the API settles;
 * it is listed as a Phase 8 improvement.
 */

export type SiteStatus = 'PENDING' | 'ACTIVE' | 'DISABLED' | 'EXPIRED' | 'ERROR';
export type DeploymentStatus = 'PENDING' | 'IN_PROGRESS' | 'SUCCESS' | 'FAILED' | 'CANCELLED';
export type CertificateStatus = 'VALID' | 'EXPIRING_SOON' | 'EXPIRED' | 'REVOKED' | 'ERROR';
export type PermissionLevel = 'READ' | 'OPERATE' | 'MANAGE' | 'ADMIN';
export type ScopeType = 'GLOBAL' | 'DOMAIN_GROUP' | 'DOMAIN_PATTERN' | 'SITE';
export type SubjectType = 'USER' | 'GROUP';

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export type Upstream = Schemas['UpstreamDto'];

export type HeaderRule = Schemas['HeaderDto'];

export type LocationRule = Schemas['LocationDto'];

export type SiteSummary = Wire<Schemas['ProxySiteSummaryResponse']>;

export type Site = Wire<Schemas['ProxySiteResponse']>;

export type SiteRequest = Schemas['ProxySiteRequest'];

export type NginxInstance = Wire<Schemas['NginxInstanceResponse']>;

export interface DeploymentEvent {
  phase: string;
  result: string;
  detail: string | null;
  at: string;
}

export type Deployment = Wire<Schemas['DeploymentResponse']>;

export type Preview = Wire<Schemas['ConfigurationPreviewResponse']>;

export type Certificate = Wire<Schemas['CertificateResponse']>;

export type DomainGroup = Wire<Schemas['DomainGroupResponse']>;

export type PermissionGrant = Wire<Schemas['PermissionGrantResponse']>;

export type EffectivePermission = Wire<Schemas['EffectivePermissionResponse']>;

export type Subject = Wire<Schemas['SubjectResponse']>;

/** RFC 9457 problem document. The `type` URI is stable; `detail` is prose and may change. */
export interface Problem {
  type: string;
  title: string;
  status: number;
  detail: string;
  instance?: string;
  errors?: { field: string; message: string }[];
  reference?: string;
}

export type AuditEntry = Wire<Schemas['AuditResponse']>;

export type UpstreamCheck = Wire<Schemas['UpstreamCheckResponse']>;

export type GrantPreview = Wire<Schemas['GrantPreviewResponse']>;

export type LocalUser = Wire<Schemas['LocalUserResponse']>;

export type CreateLocalUserRequest = Schemas['CreateLocalUserRequest'];

export type UpdateLocalUserRequest = Schemas['UpdateLocalUserRequest'];

/** The roles a local account can carry. Domain-scoped permissions are granted separately. */
export const GLOBAL_ROLES = ['SUPER_ADMIN', 'ADMIN', 'OPERATOR', 'READ_ONLY'] as const;

export type GroupMember = Wire<Schemas['GroupMemberResponse']>;

export type DirectoryEntry = Wire<Schemas['DirectoryEntryResponse']>;

export type DirectoryCleanup = Wire<Schemas['DirectoryCleanupResponse']>;
