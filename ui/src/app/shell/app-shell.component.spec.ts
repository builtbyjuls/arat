import { signal, WritableSignal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';
import { routes } from '../app.routes';
import { LocalActorSession } from '../identity/local-actor-session.service';
import { AppShellComponent } from './app-shell.component';

describe('AppShellComponent', () => {
  it('renders keyboard-operable primary navigation and focuses a route heading', async () => {
    await TestBed.configureTestingModule({
      imports: [AppShellComponent],
      providers: [
        provideRouter(routes),
        { provide: LocalActorSession, useValue: verifiedActorSession() },
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(AppShellComponent);
    fixture.detectChanges();
    const router = TestBed.inject(Router);
    await router.navigateByUrl('/groups');
    fixture.detectChanges();
    await fixture.whenStable();
    await Promise.resolve();

    const root = fixture.nativeElement as HTMLElement;
    expect(root.querySelectorAll('nav a')).toHaveLength(4);
    expect(root.querySelector<HTMLAnchorElement>('nav a')?.getAttribute('href')).toBe('/groups');
    expect(document.activeElement?.getAttribute('data-route-heading')).not.toBeNull();
  });

  it('shows an accessible not-found page for an unknown route', async () => {
    await TestBed.configureTestingModule({
      imports: [AppShellComponent],
      providers: [
        provideRouter(routes),
        { provide: LocalActorSession, useValue: verifiedActorSession() },
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(AppShellComponent);
    fixture.detectChanges();
    await TestBed.inject(Router).navigateByUrl('/missing-page');
    fixture.detectChanges();
    await fixture.whenStable();

    expect((fixture.nativeElement as HTMLElement).querySelector('h1')?.textContent)
      .toContain('Page not found');
  });

  it('removes private route content when the verified actor becomes unavailable', async () => {
    const session = verifiedActorSession();
    await TestBed.configureTestingModule({
      imports: [AppShellComponent],
      providers: [
        provideRouter(routes),
        { provide: LocalActorSession, useValue: session },
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(AppShellComponent);
    fixture.detectChanges();
    await TestBed.inject(Router).navigateByUrl('/groups');
    fixture.detectChanges();
    await fixture.whenStable();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Groups');

    session.isVerified.set(false);
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Local actor unavailable');
    expect((fixture.nativeElement as HTMLElement).textContent).not.toContain('Your private groups');
  });

  it('keeps a skip-link activation on the current deep link', async () => {
    await TestBed.configureTestingModule({
      imports: [AppShellComponent],
      providers: [
        provideRouter(routes),
        { provide: LocalActorSession, useValue: verifiedActorSession() },
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(AppShellComponent);
    fixture.detectChanges();
    const router = TestBed.inject(Router);
    await router.navigateByUrl('/plans/example');
    fixture.detectChanges();
    await fixture.whenStable();

    (fixture.nativeElement.querySelector('.skip-link') as HTMLAnchorElement).click();
    expect(router.url).toBe('/plans/example');
    expect(document.activeElement?.id).toBe('main-content');
  });

  it('keeps primary navigation controls usable at documented mobile widths', async () => {
    await TestBed.configureTestingModule({
      imports: [AppShellComponent],
      providers: [
        provideRouter(routes),
        { provide: LocalActorSession, useValue: verifiedActorSession() },
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(AppShellComponent);
    fixture.detectChanges();
    const root = fixture.nativeElement as HTMLElement;

    for (const width of [320, 360, 390]) {
      root.style.width = `${width}px`;
      const links = [...root.querySelectorAll<HTMLElement>('nav a')];
      expect(links).toHaveLength(4);
      expect(links.every((link) => getComputedStyle(link).minHeight === '44px')).toBe(true);
    }
  });
});

function verifiedActorSession(): Pick<LocalActorSession, 'actors' | 'restore' | 'isVerified'> & {
  isVerified: WritableSignal<boolean>;
} {
  return {
    actors: [{
      id: 'ari',
      label: 'Ari Organizer',
      token: 'test-token',
      actorId: '10000000-0000-4000-8000-000000000001',
      platformRoles: [],
    }],
    restore: vi.fn().mockResolvedValue(undefined),
    isVerified: signal(true),
  };
}
