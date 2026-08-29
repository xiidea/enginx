import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { GroupsApi, PermissionsApi, SitesApi } from '../../core/api/resources';
import {
  GrantPreview,
  DomainGroup,
  GroupMember,
  PermissionGrant,
  PermissionLevel,
  ScopeType,
  SiteSummary,
  Subject,
  SubjectType,
} from '../../core/api/models';
import { AuthService } from '../../core/auth/auth.service';
import { Notifications } from '../../shared/notifications';
import { EmptyState, PageHeader } from '../../shared/page';
import { Typeahead } from '../../shared/typeahead';
import { peopleSource, siteSource } from '../../core/api/suggestions';
import { DateTimePipe } from '../../shared/formatting';

@Component({
  selector: 'app-permissions',
  standalone: true,
  imports: [FormsModule, PageHeader, EmptyState, DateTimePipe, Typeahead, RouterLink],
  templateUrl: './permissions.html',
  styleUrl: './permissions.css',
})
export class Permissions implements OnInit {
  private readonly api = inject(PermissionsApi);
  private readonly groupsApi = inject(GroupsApi);
  private readonly sitesApi = inject(SitesApi);
  private readonly notifications = inject(Notifications);
  readonly auth = inject(AuthService);

  /** Bound once, not rebuilt per change detection, so the typeahead's input stays identity-stable. */
  readonly people = peopleSource(this.api);
  readonly siteChoices = siteSource(this.sitesApi);

  readonly levels: PermissionLevel[] = ['READ', 'OPERATE', 'MANAGE', 'ADMIN'];
  readonly scopeTypes: ScopeType[] = ['GLOBAL', 'DOMAIN_GROUP', 'DOMAIN_PATTERN', 'SITE'];

  readonly loading = signal(true);
  readonly grants = signal<PermissionGrant[]>([]);
  readonly groups = signal<DomainGroup[]>([]);
  readonly sites = signal<SiteSummary[]>([]);
  readonly directoryGroups = signal<Subject[]>([]);
  readonly canSeeAllGrants = signal(true);

  readonly granting = signal(false);
  readonly subjectType = signal<SubjectType>('USER');
  readonly subjectRef = signal('');
  readonly scopeType = signal<ScopeType>('DOMAIN_GROUP');
  readonly preview = signal<GrantPreview | null>(null);
  readonly previewing = signal(false);
  readonly scopeGroupId = signal('');
  readonly scopeSiteId = signal('');
  readonly domainPattern = signal('');
  readonly level = signal<PermissionLevel>('READ');
  readonly expiresAt = signal('');

  /** The group whose members are on screen, and what it contains. */
  readonly expandedGroup = signal<string | null>(null);
  readonly members = signal<GroupMember[]>([]);
  readonly membersLoading = signal(false);
  readonly addingMember = signal(false);
  readonly memberToAdd = signal('');

  readonly newGroupName = signal('');
  readonly newGroupSlug = signal('');
  readonly newGroupParent = signal('');
  readonly showGroupForm = signal(false);

  ngOnInit(): void {
    this.reload();
  }

  reload(): void {
    this.loading.set(true);

    this.api.list().subscribe({
      next: (grants) => {
        this.grants.set(grants);
        this.canSeeAllGrants.set(true);
        this.loading.set(false);
      },
      error: () => {
        // Listing every grant needs global ADMIN. A group administrator legitimately cannot, and
        // saying so is more useful than an error they can do nothing about.
        this.canSeeAllGrants.set(false);
        this.grants.set([]);
        this.loading.set(false);
      },
    });

    this.groupsApi.list().subscribe({
      next: (groups) => this.groups.set(groups),
      error: () => this.groups.set([]),
    });
    this.sitesApi.list({ size: 200, sort: ['DOMAIN,asc'] }).subscribe({
      next: (page) => this.sites.set(page.content),
      error: () => this.sites.set([]),
    });
    this.api.groups().subscribe({
      next: (groups) => this.directoryGroups.set(groups),
      error: () => this.directoryGroups.set([]),
    });
  }

  /** The body both preview() and grant() send, so the two can never describe different grants. */
  private grantBody() {
    return {
      subjectType: this.subjectType(),
      subjectRef: this.subjectRef().trim(),
      scopeType: this.scopeType(),
      scopeGroupId: this.scopeType() === 'DOMAIN_GROUP' ? this.scopeGroupId() : null,
      scopeSiteId: this.scopeType() === 'SITE' ? this.scopeSiteId() : null,
      domainPattern: this.scopeType() === 'DOMAIN_PATTERN' ? this.domainPattern().trim() : null,
      level: this.level(),
      expiresAt: this.expiresAt() ? new Date(this.expiresAt()).toISOString() : null,
    };
  }

  /**
   * Shows what this grant would reach before it is made.
   *
   * A wildcard at MANAGE looks like one line of configuration and is authority over every
   * subdomain, now and in future. Almost nobody has enumerated that set in their head.
   */
  showPreview(): void {
    this.previewing.set(true);
    this.preview.set(null);
    this.api.preview(this.grantBody()).subscribe({
      next: (preview) => {
        this.preview.set(preview);
        this.previewing.set(false);
      },
      error: (problem) => {
        this.previewing.set(false);
        this.notifications.problem(problem);
      },
    });
  }

  grant(): void {
    this.granting.set(true);
    this.api
      .grant(this.grantBody())
      .subscribe({
        next: () => {
          this.granting.set(false);
          this.subjectRef.set('');
          this.domainPattern.set('');
          this.expiresAt.set('');
          this.preview.set(null);
          this.notifications.success('Permission granted');
          this.reload();
        },
        error: (problem) => {
          this.granting.set(false);
          this.notifications.problem(problem);
        },
      });
  }

  revoke(grant: PermissionGrant): void {
    if (!confirm(`Revoke ${grant.level} for ${grant.subjectRef}?`)) {
      return;
    }
    this.api.revoke(grant.id).subscribe({
      next: () => {
        this.notifications.success('Permission revoked');
        this.reload();
      },
      error: (problem) => this.notifications.problem(problem),
    });
  }

  createGroup(): void {
    this.groupsApi
      .create({
        name: this.newGroupName().trim(),
        slug: this.newGroupSlug().trim(),
        parentId: this.newGroupParent() || null,
      })
      .subscribe({
        next: (group) => {
          this.notifications.success('Group created', group.path);
          this.newGroupName.set('');
          this.newGroupSlug.set('');
          this.showGroupForm.set(false);
          this.reload();
        },
        error: (problem) => this.notifications.problem(problem),
      });
  }

  deleteGroup(group: DomainGroup): void {
    if (!confirm(`Delete ${group.path}? Every grant made over it is revoked with it.`)) {
      return;
    }
    this.groupsApi.remove(group.id).subscribe({
      next: () => {
        this.notifications.success('Group deleted');
        this.reload();
      },
      error: (problem) => this.notifications.problem(problem),
    });
  }

  /**
   * Shows or hides a group's members.
   *
   * Loaded on expansion rather than with the tree: most of the time nobody opens any of them, and
   * a request per group would make the page cost proportional to how many groups exist.
   */
  toggleMembers(group: DomainGroup): void {
    if (this.expandedGroup() === group.id) {
      this.expandedGroup.set(null);
      return;
    }
    this.expandedGroup.set(group.id);
    this.memberToAdd.set('');
    this.loadMembers(group.id);
  }

  private loadMembers(groupId: string): void {
    this.membersLoading.set(true);
    this.members.set([]);
    this.groupsApi.members(groupId).subscribe({
      next: (members) => {
        this.members.set(members);
        this.membersLoading.set(false);
      },
      error: (problem) => {
        this.membersLoading.set(false);
        this.notifications.problem(problem);
      },
    });
  }

  addMember(group: DomainGroup): void {
    const siteId = this.memberToAdd();
    if (!siteId) {
      return;
    }
    this.addingMember.set(true);
    this.groupsApi.addMember(group.id, siteId).subscribe({
      next: () => {
        this.addingMember.set(false);
        this.memberToAdd.set('');
        this.notifications.success('Site added', `Every grant on ${group.path} now reaches it.`);
        this.loadMembers(group.id);
        // The tree shows a member count, so it is now wrong.
        this.reloadGroups();
      },
      error: (problem) => {
        this.addingMember.set(false);
        this.notifications.problem(problem);
      },
    });
  }

  removeMember(group: DomainGroup, member: GroupMember): void {
    if (!confirm(`Remove ${member.domain} from ${group.path}? Access granted through this group ends.`)) {
      return;
    }
    this.groupsApi.removeMember(group.id, member.id).subscribe({
      next: () => {
        this.notifications.success('Site removed', member.domain);
        this.loadMembers(group.id);
        this.reloadGroups();
      },
      error: (problem) => this.notifications.problem(problem),
    });
  }

  private reloadGroups(): void {
    this.groupsApi.list().subscribe({
      next: (groups) => this.groups.set(groups),
      error: () => undefined,
    });
  }

  scopeLabel(grant: PermissionGrant): string {
    switch (grant.scopeType) {
      case 'GLOBAL':
        return 'everything';
      case 'DOMAIN_GROUP':
        return this.groups().find((group) => group.id === grant.scopeGroupId)?.path ?? 'a group';
      case 'SITE':
        return this.sites().find((site) => site.id === grant.scopeSiteId)?.domain ?? 'a site';
      case 'DOMAIN_PATTERN':
        return grant.domainPattern ?? 'a pattern';
      default:
        // The wire types scopeType as a string, so a server that gains a scope type this console
        // does not know about must not crash it. Naming the unknown value is more useful than a
        // blank cell to whoever has to work out what happened.
        return grant.scopeType;
    }
  }

  /** Indentation mirrors nesting, because a grant on a parent reaches everything beneath it. */
  indentOf(group: DomainGroup): string {
    return `${(group.depth - 1) * 1.1}rem`;
  }
}
