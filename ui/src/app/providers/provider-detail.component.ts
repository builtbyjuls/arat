import { Component, OnInit, inject } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { ProviderDetailService } from './provider-detail.service';

@Component({
  imports: [RouterLink],
  selector: 'app-provider-detail',
  styleUrl: './provider-detail.component.scss',
  templateUrl: './provider-detail.component.html',
})
export class ProviderDetailComponent implements OnInit {
  readonly providerDetail = inject(ProviderDetailService);
  readonly #route = inject(ActivatedRoute);

  ngOnInit(): void {
    this.#route.paramMap.subscribe((params) => {
      const providerId = params.get('providerId');
      if (providerId !== null) void this.providerDetail.load(providerId);
    });
  }

  reload(): void {
    const providerId = this.#route.snapshot.paramMap.get('providerId');
    if (providerId !== null) void this.providerDetail.load(providerId);
  }

  roleLabel(role: string | undefined): string { return role === 'ADMIN' ? 'Admin' : 'Staff'; }
}
