import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { ApiHttpError, ApiProblemViolation } from '../api/api-http-client';
import { ActorScopeResetService } from '../identity/actor-scope-reset.service';
import { CreatePreferenceRequest, PlanApi, PlanPreference, PreferenceCollection } from './plan-api.service';

export type PreferenceLoadState = 'absent' | 'error' | 'loading' | 'ready';
export type PreferenceSaveState = 'conflict' | 'error' | 'idle' | 'submitting';

@Injectable({ providedIn: 'root' })
export class PlanPreferenceService {
  readonly #api = inject(PlanApi);
  readonly #scopeReset = inject(ActorScopeResetService);
  readonly #ownPreference = signal<PlanPreference | null>(null);
  readonly #preferenceEtag = signal<string | null>(null);
  readonly #ownState = signal<PreferenceLoadState>('loading');
  readonly #summary = signal<PreferenceCollection | null>(null);
  readonly #summaryState = signal<PreferenceLoadState>('loading');
  readonly #summaryProblemCode = signal<string | null>(null);
  readonly #saveState = signal<PreferenceSaveState>('idle');
  readonly #problemCode = signal<string | null>(null);
  readonly #correlationId = signal<string | null>(null);
  readonly #violations = signal<readonly ApiProblemViolation[]>([]);
  #planId: string | null = null;
  #readRequestId = 0;
  #writeRequestId = 0;

  readonly ownPreference = this.#ownPreference.asReadonly();
  readonly preferenceEtag = this.#preferenceEtag.asReadonly();
  readonly ownState = this.#ownState.asReadonly();
  readonly summary = this.#summary.asReadonly();
  readonly summaryState = this.#summaryState.asReadonly();
  readonly summaryProblemCode = this.#summaryProblemCode.asReadonly();
  readonly saveState = this.#saveState.asReadonly();
  readonly problemCode = this.#problemCode.asReadonly();
  readonly correlationId = this.#correlationId.asReadonly();
  readonly violations = this.#violations.asReadonly();

  constructor() { this.#scopeReset.register(() => this.reset()); }

  usePlan(planId: string): void {
    if (this.#planId !== planId) {
      this.reset();
      this.#planId = planId;
    }
  }

  releasePlan(planId: string): void {
    if (this.#planId === planId) {
      this.reset();
    }
  }

  async load(planId: string): Promise<void> {
    this.usePlan(planId);
    const requestId = ++this.#readRequestId;
    this.#ownPreference.set(null);
    this.#preferenceEtag.set(null);
    this.#summary.set(null);
    this.#ownState.set('loading');
    this.#summaryState.set('loading');
    this.#summaryProblemCode.set(null);
    await Promise.all([this.loadOwn(planId, requestId), this.loadSummary(planId, requestId)]);
  }

  async save(planId: string, request: CreatePreferenceRequest): Promise<PlanPreference | null> {
    if (this.#planId !== planId || this.#saveState() === 'submitting') {
      return null;
    }
    const requestId = ++this.#writeRequestId;
    this.#saveState.set('submitting');
    this.#problemCode.set(null);
    this.#correlationId.set(null);
    this.#violations.set([]);
    try {
      const result = await firstValueFrom(this.#api.putPreference(planId, request, this.#preferenceEtag()));
      if (!this.isCurrentWrite(requestId, planId)) {
        return null;
      }
      if (result.body === null || result.etag === null) {
        this.#ownPreference.set(null);
        this.#preferenceEtag.set(null);
        this.#ownState.set('error');
        this.#problemCode.set('INVALID_PREFERENCE_RESPONSE');
        this.#saveState.set('error');
        return null;
      }
      this.#ownPreference.set(result.body);
      this.#preferenceEtag.set(result.etag);
      this.#ownState.set('ready');
      this.#saveState.set('idle');
      void this.refreshSummary(planId);
      return result.body;
    } catch (error: unknown) {
      if (!this.isCurrentWrite(requestId, planId)) {
        return null;
      }
      const apiError = error instanceof ApiHttpError ? error : null;
      this.#problemCode.set(apiError?.problem.code ?? null);
      this.#correlationId.set(apiError?.correlationId ?? null);
      this.#violations.set(apiError?.problem.violations ?? []);
      this.#saveState.set(apiError?.status === 412 || apiError?.problem.code === 'REQUIREMENT_VERSION_CHANGED'
        ? 'conflict'
        : 'error');
      return null;
    }
  }

  dismiss(): void {
    this.#saveState.set('idle');
    this.#problemCode.set(null);
    this.#correlationId.set(null);
    this.#violations.set([]);
  }

  private async loadOwn(planId: string, requestId: number): Promise<void> {
    try {
      const result = await firstValueFrom(this.#api.readOwnPreference(planId));
      if (!this.isCurrentRead(requestId, planId)) { return; }
      if (result.body === null || result.etag === null) {
        this.#ownState.set('error');
        return;
      }
      this.#ownPreference.set(result.body);
      this.#preferenceEtag.set(result.etag);
      this.#ownState.set('ready');
    } catch (error: unknown) {
      if (!this.isCurrentRead(requestId, planId)) { return; }
      const apiError = error instanceof ApiHttpError ? error : null;
      this.#ownState.set(apiError?.status === 404 ? 'absent' : 'error');
    }
  }

  private async loadSummary(planId: string, requestId: number): Promise<void> {
    try {
      const result = await firstValueFrom(this.#api.listPreferences(planId));
      if (!this.isCurrentRead(requestId, planId)) { return; }
      if (result.body === null) {
        this.#summaryState.set('error');
        return;
      }
      this.#summary.set(result.body);
      this.#summaryState.set('ready');
    } catch (error: unknown) {
      if (this.isCurrentRead(requestId, planId)) {
        const apiError = error instanceof ApiHttpError ? error : null;
        this.#summaryProblemCode.set(apiError?.problem.code ?? null);
        this.#summaryState.set('error');
      }
    }
  }

  private async refreshSummary(planId: string): Promise<void> {
    const requestId = ++this.#readRequestId;
    this.#summary.set(null);
    this.#summaryState.set('loading');
    this.#summaryProblemCode.set(null);
    await this.loadSummary(planId, requestId);
  }

  private reset(): void {
    this.#readRequestId += 1;
    this.#writeRequestId += 1;
    this.#planId = null;
    this.#ownPreference.set(null);
    this.#preferenceEtag.set(null);
    this.#ownState.set('loading');
    this.#summary.set(null);
    this.#summaryState.set('loading');
    this.#summaryProblemCode.set(null);
    this.dismiss();
  }

  private isCurrentRead(requestId: number, planId: string): boolean {
    return requestId === this.#readRequestId && planId === this.#planId;
  }

  private isCurrentWrite(requestId: number, planId: string): boolean {
    return requestId === this.#writeRequestId && planId === this.#planId;
  }
}
