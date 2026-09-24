import { provideRouter } from '@angular/router';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { App } from './app';
import { routes } from './app.routes';
import { App as LocalDemoApp } from './app.local-demo';
import { LocalActorSession } from './identity/local-actor-session.service';

describe('App', () => {
  it('renders the application shell', async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideRouter(routes),
        { provide: LocalActorSession, useValue: { actors: null, isVerified: signal(false) } },
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    await fixture.whenStable();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('header')).not.toBeNull();
    expect(compiled.querySelector('nav[aria-label="Primary navigation"]')).not.toBeNull();
  });

  it('changes the selected local actor through the local-demo shell', async () => {
    const select = vi.fn().mockResolvedValue(undefined);
    const session = {
      actors: [{ id: 'bea', label: 'Bea Member' }],
      selectedPersonaId: signal<string | null>(null),
      state: signal<'idle'>('idle'),
      isVerified: signal(false),
      actor: signal(null),
      restore: vi.fn().mockResolvedValue(undefined),
      select,
    };

    await TestBed.configureTestingModule({
      imports: [LocalDemoApp],
      providers: [
        provideRouter(routes),
        { provide: LocalActorSession, useValue: session },
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(LocalDemoApp);
    fixture.detectChanges();
    const actorSelect = fixture.nativeElement.querySelector('#local-actor') as HTMLSelectElement;
    actorSelect.value = 'bea';
    actorSelect.dispatchEvent(new Event('change'));

    expect(select).toHaveBeenCalledWith('bea');
  });
});
