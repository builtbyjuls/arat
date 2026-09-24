import { Routes } from '@angular/router';
import { localActorRouteGuard } from './identity/local-actor-route.guard';

const privateRoute = {
  canActivate: [localActorRouteGuard],
  loadComponent: () => import('./shell/route-placeholder.component')
    .then((component) => component.RoutePlaceholderComponent),
};

export const routes: Routes = [
  {
    path: '',
    pathMatch: 'full',
    loadComponent: privateRoute.loadComponent,
    data: {
      title: 'Welcome to Arat',
      summary: 'Choose a workspace from the navigation to continue.',
    },
  },
  {
    path: 'groups',
    canActivate: [localActorRouteGuard],
    loadComponent: () => import('./groups/group-index.component')
      .then((component) => component.GroupIndexComponent),
    data: { requiresIdentity: true },
  },
  {
    path: 'groups/:groupId/plans',
    ...privateRoute,
    data: { requiresIdentity: true, title: 'Plans', summary: 'This group\'s plans will appear here.' },
  },
  {
    path: 'groups/:groupId',
    canActivate: [localActorRouteGuard],
    loadComponent: () => import('./groups/group-detail.component')
      .then((component) => component.GroupDetailComponent),
    data: { requiresIdentity: true },
  },
  {
    path: 'invitations/accept',
    ...privateRoute,
    data: { requiresIdentity: true, title: 'Accept invitation', summary: 'Invitation acceptance will appear here.' },
  },
  {
    path: 'plans/:planId',
    ...privateRoute,
    data: { requiresIdentity: true, title: 'Plan', summary: 'Plan details will appear here.' },
  },
  {
    path: 'providers',
    ...privateRoute,
    data: { requiresIdentity: true, title: 'Providers', summary: 'Your provider workspaces will appear here.' },
  },
  {
    path: 'providers/:providerId/requests',
    ...privateRoute,
    data: { requiresIdentity: true, title: 'Provider requests', summary: 'Eligible provider requests will appear here.' },
  },
  {
    path: 'providers/:providerId',
    ...privateRoute,
    data: { requiresIdentity: true, title: 'Provider', summary: 'Provider details will appear here.' },
  },
  {
    path: 'operations/provider-verifications',
    ...privateRoute,
    data: { requiresIdentity: true, title: 'Provider verification review', summary: 'Pending reviews will appear here.' },
  },
  {
    path: '**',
    loadComponent: () => import('./shell/not-found.component')
      .then((component) => component.NotFoundComponent),
  },
];
