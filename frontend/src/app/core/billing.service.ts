import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { EligibilityRequest, EligibilityResponse, InvoiceResponse, PaymentRequest } from './models';

@Injectable({ providedIn: 'root' })
export class BillingService {
  constructor(private http: HttpClient) {}

  getInvoiceById(id: string): Observable<InvoiceResponse> {
    return this.http.get<InvoiceResponse>(`/api/v1/invoices/${id}`);
  }

  pay(id: string, request: PaymentRequest): Observable<InvoiceResponse> {
    return this.http.post<InvoiceResponse>(`/api/v1/invoices/${id}/payments`, request);
  }

  checkEligibility(request: EligibilityRequest): Observable<EligibilityResponse> {
    return this.http.post<EligibilityResponse>('/api/v1/billing/eligibility', request);
  }
}
