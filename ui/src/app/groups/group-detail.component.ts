import { Component, OnInit, inject } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { GroupDetailService } from './group-detail.service';

@Component({
  imports: [RouterLink],
  selector: 'app-group-detail',
  styleUrl: './group-detail.component.scss',
  templateUrl: './group-detail.component.html',
})
export class GroupDetailComponent implements OnInit {
  readonly groupDetail = inject(GroupDetailService);
  readonly #route = inject(ActivatedRoute);

  ngOnInit(): void {
    this.#route.paramMap.subscribe((params) => {
      const groupId = params.get('groupId');
      if (groupId !== null) {
        void this.groupDetail.load(groupId);
      }
    });
  }

  reload(): void {
    const groupId = this.#route.snapshot.paramMap.get('groupId');
    if (groupId !== null) {
      void this.groupDetail.load(groupId);
    }
  }
}
