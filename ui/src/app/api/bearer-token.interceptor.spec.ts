import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ApiHttpClient } from './api-http-client';
import {
  BEARER_TOKEN_READER,
  bearerTokenInterceptor,
} from './bearer-token.interceptor';

describe('bearerTokenInterceptor', () => {
  afterEach(() => {
    TestBed.inject(HttpTestingController).verify();
  });

  it('adds the current bearer token to API requests', () => {
    TestBed.configureTestingModule({
      providers: [
        ApiHttpClient,
        provideHttpClient(withInterceptors([bearerTokenInterceptor])),
        provideHttpClientTesting(),
        { provide: BEARER_TOKEN_READER, useValue: () => 'current-token' },
      ],
    });

    TestBed.inject(ApiHttpClient).read('/groups').subscribe();

    const request = TestBed.inject(HttpTestingController).expectOne('/api/v1/groups');
    expect(request.request.headers.get('Authorization')).toBe('Bearer current-token');
    request.flush({ items: [], nextCursor: null });
  });

  it('sends no credential when production has no token', () => {
    TestBed.configureTestingModule({
      providers: [
        ApiHttpClient,
        provideHttpClient(withInterceptors([bearerTokenInterceptor])),
        provideHttpClientTesting(),
      ],
    });

    TestBed.inject(ApiHttpClient).read('/groups').subscribe();

    const request = TestBed.inject(HttpTestingController).expectOne('/api/v1/groups');
    expect(request.request.headers.has('Authorization')).toBe(false);
    request.flush({ items: [], nextCursor: null });
  });
});
