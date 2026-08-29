import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AuthService } from './core/auth/auth.service';
import { Toasts } from './shared/notifications';

type Theme = 'system' | 'light' | 'dark';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive, Toasts],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App implements OnInit {
  readonly auth = inject(AuthService);
  readonly theme = signal<Theme>(readStoredTheme());

  private readonly allNavigation = [
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
  ];

  readonly navigation = computed(() =>
    this.allNavigation.filter((item) => !item.adminOnly || this.auth.isSuperAdmin()),
  );

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
