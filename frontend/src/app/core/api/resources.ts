import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';
import {
  AuditEntry,
  GrantPreview,
  Certificate,
  Deployment,
  DomainGroup,
  CreateLocalUserRequest,
  GroupMember,
  EffectivePermission,
  LocalUser,
  NginxInstance,
  Page,
  PermissionGrant,
  PermissionLevel,
  Preview,
  ScopeType,
  Site,
  SiteRequest,
  SiteStatus,
  SiteSummary,
  Subject,
  SubjectType,
  UpdateLocalUserRequest,
  UpstreamCheck,
} from './models';

export interface SubjectQuery {
  search?: string;
  page?: number;
  size?: number;
}

export interface SiteQuery {
  search?: string;
  status?: SiteStatus[];
  nginxInstanceId?: string;
  sslEnabled?: boolean;
  expiringBefore?: string;
  page?: number;
  size?: number;
  sort?: string[];
}

@Injectable({ providedIn: 'root' })
export class SitesApi {
  private readonly api = inject(ApiService);

  list(query: SiteQuery = {}): Observable<Page<SiteSummary>> {
    return this.api.get('/proxy-sites', { ...query, status: query.status, sort: query.sort });
  }

  get(id: string): Observable<Site> {
    return this.api.get(`/proxy-sites/${id}`);
  }

  create(request: SiteRequest): Observable<Site> {
    return this.api.post('/proxy-sites', request);
  }

  update(id: string, request: SiteRequest, version: number): Observable<Site> {
    return this.api.put(`/proxy-sites/${id}`, request, version);
  }

  remove(id: string, version: number): Observable<void> {
    return this.api.delete(`/proxy-sites/${id}`, version);
  }

  enable(id: string): Observable<Site> {
    return this.api.post(`/proxy-sites/${id}/enable`);
  }

  disable(id: string): Observable<Site> {
    return this.api.post(`/proxy-sites/${id}/disable`);
  }

  renew(id: string, expiresAt: string, version: number): Observable<Site> {
    return this.api.post(`/proxy-sites/${id}/renew`, { expiresAt });
  }

  removeExpiry(id: string): Observable<Site> {
    return this.api.delete(`/proxy-sites/${id}/expiration`);
  }

  clone(id: string, name: string, domain: string): Observable<Site> {
    return this.api.post(`/proxy-sites/${id}/clone`, { name, domain });
  }

  preview(id: string): Observable<Preview> {
    return this.api.get(`/proxy-sites/${id}/preview`);
  }

  /** Asks the site's NGINX host whether it can open a connection to each upstream. */
  checkUpstreams(id: string): Observable<UpstreamCheck[]> {
    return this.api.get(`/proxy-sites/${id}/upstream-check`);
  }

  deploy(id: string): Observable<Deployment> {
    return this.api.post(`/proxy-sites/${id}/deploy`);
  }
}

@Injectable({ providedIn: 'root' })
export class DeploymentsApi {
  private readonly api = inject(ApiService);

  list(nginxInstanceId?: string, page = 0, size = 25): Observable<Page<Deployment>> {
    return this.api.get('/deployments', { nginxInstanceId, page, size });
  }

  get(id: string): Observable<Deployment> {
    return this.api.get(`/deployments/${id}`);
  }

  rollback(id: string): Observable<Deployment> {
    return this.api.post(`/deployments/${id}/rollback`);
  }
}

@Injectable({ providedIn: 'root' })
export class InstancesApi {
  private readonly api = inject(ApiService);

  list(): Observable<NginxInstance[]> {
    return this.api.get('/nginx-instances');
  }

  register(body: {
    name: string;
    hostname: string;
    agentBaseUrl: string;
    agentCertFingerprint: string;
    environment?: string;
  }): Observable<NginxInstance> {
    return this.api.post('/nginx-instances', body);
  }

  deploy(id: string): Observable<Deployment> {
    return this.api.post(`/nginx-instances/${id}/deploy`);
  }
}

@Injectable({ providedIn: 'root' })
export class CertificatesApi {
  private readonly api = inject(ApiService);

  list(): Observable<Certificate[]> {
    return this.api.get('/certificates');
  }

  get(id: string): Observable<Certificate> {
    return this.api.get(`/certificates/${id}`);
  }

  request(body: {
    name: string;
    domains: string[];
    autoRenew: boolean;
    renewBeforeDays: number;
  }): Observable<Certificate> {
    return this.api.post('/certificates', body);
  }

  upload(body: { name: string; fullChainPem: string; privateKeyPem: string }): Observable<Certificate> {
    return this.api.post('/certificates/upload', body);
  }

  renew(id: string): Observable<Certificate> {
    return this.api.post(`/certificates/${id}/renew`);
  }

  configureRenewal(id: string, autoRenew: boolean, renewBeforeDays: number): Observable<Certificate> {
    return this.api.put(`/certificates/${id}/renewal`, { autoRenew, renewBeforeDays });
  }

  revoke(id: string): Observable<Certificate> {
    return this.api.post(`/certificates/${id}/revoke`);
  }

  remove(id: string): Observable<void> {
    return this.api.delete(`/certificates/${id}`);
  }
}

@Injectable({ providedIn: 'root' })
export class GroupsApi {
  private readonly api = inject(ApiService);

  /**
   * @param siteId when given, only the groups that site is filed under. Answered by the server
   *               because the client would otherwise have to ask every group in turn.
   */
  list(siteId?: string): Observable<DomainGroup[]> {
    return this.api.get('/domain-groups', { siteId });
  }

  create(body: { name: string; slug: string; description?: string; parentId?: string | null }): Observable<DomainGroup> {
    return this.api.post('/domain-groups', body);
  }

  rename(id: string, name: string, description?: string): Observable<DomainGroup> {
    return this.api.put(`/domain-groups/${id}`, { name, description });
  }

  remove(id: string): Observable<void> {
    return this.api.delete(`/domain-groups/${id}`);
  }

  members(id: string): Observable<GroupMember[]> {
    return this.api.get(`/domain-groups/${id}/members`);
  }

  addMember(id: string, siteId: string): Observable<void> {
    return this.api.post(`/domain-groups/${id}/members/${siteId}`);
  }

  removeMember(id: string, siteId: string): Observable<void> {
    return this.api.delete(`/domain-groups/${id}/members/${siteId}`);
  }
}

@Injectable({ providedIn: 'root' })
export class PermissionsApi {
  private readonly api = inject(ApiService);

  list(filter: { groupId?: string; siteId?: string } = {}): Observable<PermissionGrant[]> {
    return this.api.get('/permissions', filter);
  }

  /** What a grant would reach, without creating it. Authorised exactly like grant(). */
  preview(body: {
    subjectType: SubjectType;
    subjectRef: string;
    scopeType: ScopeType;
    scopeGroupId?: string | null;
    scopeSiteId?: string | null;
    domainPattern?: string | null;
    level: PermissionLevel;
    expiresAt?: string | null;
  }): Observable<GrantPreview> {
    return this.api.post('/permissions/preview', body);
  }

  grant(body: {
    subjectType: SubjectType;
    subjectRef: string;
    scopeType: ScopeType;
    scopeGroupId?: string | null;
    scopeSiteId?: string | null;
    domainPattern?: string | null;
    level: PermissionLevel;
    expiresAt?: string | null;
  }): Observable<PermissionGrant> {
    return this.api.post('/permissions', body);
  }

  revoke(id: string): Observable<void> {
    return this.api.delete(`/permissions/${id}`);
  }

  /** What the current user may do to one site, so the console can grey out what would be refused. */
  effective(siteId: string): Observable<EffectivePermission> {
    return this.api.get('/permissions/effective', { siteId });
  }

  /**
   * Searches the people who have signed in.
   *
   * Paged and searched on the server: this index gains a row for every person who ever signs in,
   * so on a real directory it is far past what a dropdown can hold by the time anyone notices.
   */
  users(query: SubjectQuery = {}): Observable<Page<Subject>> {
    return this.api.get('/users', { search: query.search, page: query.page, size: query.size });
  }

  groups(): Observable<Subject[]> {
    return this.api.get('/groups');
  }
}

export interface AuditQuery {
  actor?: string;
  action?: string[];
  result?: string[];
  resourceType?: string;
  resourceId?: string;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}

@Injectable({ providedIn: 'root' })
export class AuditApi {
  private readonly api = inject(ApiService);

  search(query: AuditQuery = {}): Observable<Page<AuditEntry>> {
    return this.api.get('/audit-logs', { ...query, action: query.action, result: query.result });
  }

  /** The action vocabulary, so the filter offers what the server can actually match. */
  actions(): Observable<string[]> {
    return this.api.get('/audit-logs/actions');
  }
}

@Injectable({ providedIn: 'root' })
export class LocalUsersApi {
  private readonly api = inject(ApiService);

  list(): Observable<LocalUser[]> {
    return this.api.get('/local-users');
  }

  create(request: CreateLocalUserRequest): Observable<LocalUser> {
    return this.api.post('/local-users', request);
  }

  update(id: string, request: UpdateLocalUserRequest): Observable<LocalUser> {
    return this.api.put(`/local-users/${id}`, request);
  }

  setEnabled(id: string, enabled: boolean): Observable<LocalUser> {
    return this.api.post(`/local-users/${id}/${enabled ? 'enable' : 'disable'}`);
  }

  /**
   * Changing your own password requires the current one; an administrator resetting somebody
   * else's neither has it nor needs it.
   */
  changePassword(id: string, newPassword: string, currentPassword?: string): Observable<void> {
    return this.api.put(`/local-users/${id}/password`, { currentPassword, newPassword });
  }

  remove(id: string): Observable<void> {
    return this.api.delete(`/local-users/${id}`);
  }
}
