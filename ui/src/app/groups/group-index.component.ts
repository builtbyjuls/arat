import { Component, OnInit, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { GroupIndexService } from './group-index.service';

@Component({
  imports: [RouterLink],
  selector: 'app-group-index',
  styleUrl: './group-index.component.scss',
  templateUrl: './group-index.component.html',
})
export class GroupIndexComponent implements OnInit {
  readonly groupIndex = inject(GroupIndexService);

  ngOnInit(): void {
    void this.groupIndex.refresh();
  }

  refresh(): void {
    void this.groupIndex.refresh();
  }

  loadMore(): void {
    void this.groupIndex.loadMore();
  }
}
