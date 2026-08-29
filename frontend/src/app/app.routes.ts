import { Routes } from '@angular/router';
import { authGuard } from './core/auth/auth.guard';

/**
 * Every feature is lazily loaded. The console is used a page at a time, and an operator who only
 * ever looks at proxy sites should not download the certificate and permission screens to do it.
 */
export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
  {
    path: 'dashboard',
    canActivate: [authGuard],
    loadComponent: () => import('./features/dashboard/dashboard').then((m) => m.Dashboard),
    title: 'Dashboard · Easy NGINX Admin',
  },
  {
    path: 'sites',
    canActivate: [authGuard],
    loadComponent: () => import('./features/sites/site-list').then((m) => m.SiteList),
    title: 'Proxy sites · Easy NGINX Admin',
  },
  {
    path: 'sites/new',
    canActivate: [authGuard],
    loadComponent: () => import('./features/sites/site-form').then((m) => m.SiteForm),
    title: 'New proxy site · Easy NGINX Admin',
  },
  {
    path: 'sites/:id',
    canActivate: [authGuard],
    loadComponent: () => import('./features/sites/site-detail').then((m) => m.SiteDetail),
    title: 'Proxy site · Easy NGINX Admin',
  },
  {
    path: 'sites/:id/edit',
    canActivate: [authGuard],
    loadComponent: () => import('./features/sites/site-form').then((m) => m.SiteForm),
    title: 'Edit proxy site · Easy NGINX Admin',
  },
  {
    path: 'certificates',
    canActivate: [authGuard],
    loadComponent: () => import('./features/certificates/certificate-list').then((m) => m.CertificateList),
    title: 'Certificates · Easy NGINX Admin',
  },
  {
    path: 'deployments',
    canActivate: [authGuard],
    loadComponent: () => import('./features/deployments/deployment-list').then((m) => m.DeploymentList),
    title: 'Deployments · Easy NGINX Admin',
  },
  {
    path: 'permissions',
    canActivate: [authGuard],
    loadComponent: () => import('./features/permissions/permissions').then((m) => m.Permissions),
    title: 'Permissions · Easy NGINX Admin',
  },
  {
    path: 'instances',
    canActivate: [authGuard],
    loadComponent: () => import('./features/instances/instance-list').then((m) => m.InstanceList),
    title: 'NGINX instances · Easy NGINX Admin',
  },
  {
    path: 'audit',
    canActivate: [authGuard],
    loadComponent: () => import('./features/audit/audit-log').then((m) => m.AuditLog),
    title: 'Audit log · Easy NGINX Admin',
  },
  {
    path: 'local-users',
    canActivate: [authGuard],
    loadComponent: () => import('./features/auth/local-user-list').then((m) => m.LocalUserList),
    title: 'Local users · Easy NGINX Admin',
  },
  {
    path: 'directory',
    canActivate: [authGuard],
    loadComponent: () => import('./features/directory/directory').then((m) => m.Directory),
    title: 'Directory · Easy NGINX Admin',
  },
  { path: '**', redirectTo: 'dashboard' },
];
