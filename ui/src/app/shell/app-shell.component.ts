import { AfterViewInit, Component, DestroyRef, ElementRef, computed, inject, signal } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet, NavigationEnd, Router, ActivatedRouteSnapshot } from '@angular/router';
import { filter } from 'rxjs';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { LocalActorSession } from '../identity/local-actor-session.service';

@Component({
  imports: [RouterLink, RouterLinkActive, RouterOutlet],
  selector: 'app-shell',
  styleUrl: './app-shell.component.scss',
  templateUrl: './app-shell.component.html',
})
export class AppShellComponent implements AfterViewInit {
  private readonly router = inject(Router);
  private readonly element = inject(ElementRef<HTMLElement>);
  private readonly destroyRef = inject(DestroyRef);
  private readonly actorSession = inject(LocalActorSession);
  private readonly privateRouteActive = signal(false);
  readonly showRoute = computed(() => !this.privateRouteActive()
    || this.actorSession.actors === null
    || this.actorSession.isVerified());

  ngAfterViewInit(): void {
    this.router.events.pipe(
      filter((event): event is NavigationEnd => event instanceof NavigationEnd),
      takeUntilDestroyed(this.destroyRef),
    ).subscribe(() => {
      this.setPrivateRouteState();
      queueMicrotask(() => this.focusRouteHeading());
    });
    this.setPrivateRouteState();
  }

  skipToContent(event: MouseEvent): void {
    event.preventDefault();
    (this.element.nativeElement as HTMLElement).querySelector<HTMLElement>('#main-content')?.focus();
  }

  private focusRouteHeading(): void {
    (this.element.nativeElement as HTMLElement)
      .querySelector<HTMLElement>('[data-route-heading]')?.focus();
  }

  private setPrivateRouteState(): void {
    let route: ActivatedRouteSnapshot | null = this.router.routerState.snapshot.root;
    while (route?.firstChild) {
      route = route.firstChild;
    }
    this.privateRouteActive.set(route?.data['requiresIdentity'] === true);
  }
}
