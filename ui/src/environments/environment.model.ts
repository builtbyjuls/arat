export interface LocalDemoActor {
  readonly id: string;
  readonly label: string;
  readonly token: string;
  readonly actorId: string;
  readonly platformRoles: readonly string[];
}

export interface Environment {
  readonly apiBasePath: string;
  readonly readRetryCount: number;
  readonly localDemo: readonly LocalDemoActor[] | null;
}
