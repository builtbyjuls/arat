import { Environment } from './environment.model';

export const environment: Environment = {
  apiBasePath: '/api/v1',
  readRetryCount: 2,
  localDemo: [
    {
      id: 'ari',
      label: 'Ari Organizer',
      token: 'arat-local-owner-token',
      actorId: '10000000-0000-4000-8000-000000000001',
      platformRoles: [],
    },
    {
      id: 'bea',
      label: 'Bea Member',
      token: 'arat-local-member-token',
      actorId: '10000000-0000-4000-8000-000000000002',
      platformRoles: [],
    },
    {
      id: 'cruz',
      label: 'Cruz Outsider',
      token: 'arat-local-outsider-token',
      actorId: '10000000-0000-4000-8000-000000000003',
      platformRoles: [],
    },
    {
      id: 'dani',
      label: 'Dani Provider',
      token: 'arat-local-provider-token',
      actorId: '10000000-0000-4000-8000-000000000004',
      platformRoles: [],
    },
    {
      id: 'owen',
      label: 'Owen Operator',
      token: 'arat-local-operator-token',
      actorId: '10000000-0000-4000-8000-000000000005',
      platformRoles: ['PLATFORM_OPERATOR'],
    },
  ],
};
