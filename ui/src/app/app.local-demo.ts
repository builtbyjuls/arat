import { Component, OnInit, computed, inject } from '@angular/core';
import { LocalActorSession, unavailableMessage } from './identity/local-actor-session.service';
import { AppShellComponent } from './shell/app-shell.component';

@Component({
  imports: [AppShellComponent],
  selector: 'app-root',
  styleUrl: './app.local-demo.scss',
  templateUrl: './app.local-demo.html',
})
export class App implements OnInit {
  readonly actorSession = inject(LocalActorSession);
  readonly unavailableMessage = computed(() => unavailableMessage(this.actorSession.state()));

  ngOnInit(): void {
    void this.actorSession.restore();
  }

  selectActor(event: Event): void {
    const select = event.target as HTMLSelectElement;
    void this.actorSession.select(select.value);
  }
}
