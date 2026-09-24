import { Component, OnInit, inject } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { PlanIndexService } from './plan-index.service';

@Component({
  imports: [RouterLink],
  selector: 'app-plan-index',
  styleUrl: './plan-index.component.scss',
  templateUrl: './plan-index.component.html',
})
export class PlanIndexComponent implements OnInit {
  readonly planIndex = inject(PlanIndexService);
  readonly #route = inject(ActivatedRoute);

  ngOnInit(): void {
    this.#route.paramMap.subscribe((params) => {
      const groupId = params.get('groupId');
      if (groupId !== null) { void this.planIndex.refresh(groupId); }
    });
  }

  refresh(): void { const groupId = this.groupId(); if (groupId !== null) { void this.planIndex.refresh(groupId); } }
  loadMore(): void { void this.planIndex.loadMore(); }
  private groupId(): string | null { return this.#route.snapshot.paramMap.get('groupId'); }
}
