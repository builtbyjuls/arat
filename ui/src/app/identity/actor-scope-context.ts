import { HttpContextToken } from '@angular/common/http';

export const ACTOR_SCOPE_GENERATION = new HttpContextToken<number | null>(() => null);
