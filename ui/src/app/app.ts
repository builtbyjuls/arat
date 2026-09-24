import { Component } from '@angular/core';
import { AppShellComponent } from './shell/app-shell.component';

@Component({
  imports: [AppShellComponent],
  selector: 'app-root',
  styleUrl: './app.scss',
  templateUrl: './app.html',
})
export class App {
}
