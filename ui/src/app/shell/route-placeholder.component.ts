import { Component, inject } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { SharedViewStateComponent } from './shared-view-state.component';

@Component({
  imports: [SharedViewStateComponent],
  selector: 'app-route-placeholder',
  styleUrl: './route-placeholder.component.scss',
  templateUrl: './route-placeholder.component.html',
})
export class RoutePlaceholderComponent {
  private readonly route = inject(ActivatedRoute);
  readonly title = String(this.route.snapshot.data['title']);
  readonly summary = String(this.route.snapshot.data['summary']);
}
