import { Component, OnInit, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ProviderIndexService } from './provider-index.service';

@Component({
  imports: [RouterLink],
  selector: 'app-provider-index',
  styleUrl: './provider-index.component.scss',
  templateUrl: './provider-index.component.html',
})
export class ProviderIndexComponent implements OnInit {
  readonly providerIndex = inject(ProviderIndexService);

  ngOnInit(): void {
    void this.providerIndex.refresh();
  }

  refresh(): void {
    void this.providerIndex.refresh();
  }

  loadMore(): void {
    void this.providerIndex.loadMore();
  }

  roleLabel(role: string | undefined): string {
    return role === 'ADMIN' ? 'Admin' : 'Staff';
  }
}
