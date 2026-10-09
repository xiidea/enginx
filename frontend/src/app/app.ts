import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AuthService } from './core/auth/auth.service';
import { environment } from '../environments/environment';
import { PasswordChange } from './features/auth/password-change';
import { SignIn } from './features/auth/sign-in';
import { Toasts } from './shared/notifications';

type Theme = 'system' | 'light' | 'dark';

interface NavigationItem {
  path: string;
  label: string;
  /** Hidden without a global admin role. The server refuses the underlying endpoint regardless. */
  adminOnly: boolean;
  /** Hidden unless the platform authenticates people itself. */
  localOnly?: boolean;
}

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive, Toasts, SignIn, PasswordChange],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App implements OnInit {
  readonly auth = inject(AuthService);
  readonly theme = signal<Theme>(readStoredTheme());

  private readonly allNavigation: NavigationItem[] = [
    { path: '/dashboard', label: 'Dashboard', adminOnly: false },
    { path: '/sites', label: 'Proxy sites', adminOnly: false },
    { path: '/certificates', label: 'Certificates', adminOnly: false },
    { path: '/deployments', label: 'Deployments', adminOnly: false },
    { path: '/permissions', label: 'Permissions', adminOnly: false },
    { path: '/instances', label: 'NGINX instances', adminOnly: false },
    // The trail spans every domain in the estate, so the server requires global admin for it.
    // Hiding the link is a courtesy to everyone else, not the control: the endpoint refuses the
    // request regardless of what this list says.
    { path: '/audit', label: 'Audit log', adminOnly: true },
    // Only exists when the platform authenticates people itself; with an external provider the
    // accounts live there and this page would have nothing to show.
    { path: '/local-users', label: 'Local users', adminOnly: true, localOnly: true },
    // Everyone who has signed in, and the removal of entries that no longer resolve. Global
    // admin, because the list is shared by everyone who authors a grant.
    { path: '/directory', label: 'Directory', adminOnly: true },
  ];

  readonly navigation = computed(() =>
    this.allNavigation.filter(
      (item) =>
        (!item.adminOnly || this.auth.isSuperAdmin())
        && (!item.localOnly || this.auth.methods().localEnabled),
    ),
  );

  /**
   * Both halves of the platform, so a console and server left at different releases after a partial
   * upgrade is visible at a glance rather than discovered through a broken page.
   */
  readonly versions = computed(() => {
    const server = this.auth.methods().serverVersion;
    return server && server !== environment.version
      ? `console ${environment.version} · server ${server}`
      : `v${server ?? environment.version}`;
  });

  /** The realm roles, for the sidebar. Domain-scoped grants are shown on the pages they affect. */
  roleLabel(): string {
    const roles = [...this.auth.roles()];
    return roles.length ? roles.join(' · ') : 'no roles';
  }

  ngOnInit(): void {
    // Authentication is resolved by an application initializer, before the router runs.
    this.applyTheme(this.theme());
  }

  cycleTheme(): void {
    const order: Theme[] = ['system', 'light', 'dark'];
    const next = order[(order.indexOf(this.theme()) + 1) % order.length];
    this.theme.set(next);
    this.applyTheme(next);
  }

  private applyTheme(theme: Theme): void {
    // "system" removes the stamp entirely rather than guessing, so prefers-color-scheme decides.
    if (theme === 'system') {
      document.documentElement.removeAttribute('data-theme');
    } else {
      document.documentElement.setAttribute('data-theme', theme);
    }
    try {
      localStorage.setItem('enginx.theme', theme);
    } catch {
      // Private windows and blocked site data throw here. A remembered preference is a
      // convenience, so losing it must not break the page.
    }
  }
}

function readStoredTheme(): Theme {
  try {
    const stored = localStorage.getItem('enginx.theme');
    return stored === 'light' || stored === 'dark' ? stored : 'system';
  } catch {
    return 'system';
  }
}
