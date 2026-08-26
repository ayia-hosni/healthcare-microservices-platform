import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { BillingService } from '../../core/billing.service';
import { EligibilityResponse, InvoiceResponse } from '../../core/models';

@Component({
  selector: 'app-billing',
  imports: [CommonModule, FormsModule],
  templateUrl: './billing.component.html'
})
export class BillingComponent {
  // Eligibility check
  memberId = '';
  payerId = '';
  dateOfBirth = '';
  eligibilityResult: EligibilityResponse | null = null;
  eligibilityError = '';
  checkingEligibility = false;

  // Invoice lookup + payment
  invoiceId = '';
  invoice: InvoiceResponse | null = null;
  invoiceError = '';
  lookingUpInvoice = false;

  paymentAmount: number | null = null;
  paymentMethod = 'CARD';
  paying = false;
  paymentError = '';

  constructor(private billingService: BillingService) {}

  checkEligibility(): void {
    this.checkingEligibility = true;
    this.eligibilityError = '';
    this.eligibilityResult = null;
    this.billingService
      .checkEligibility({
        memberId: this.memberId.trim(),
        payerId: this.payerId.trim(),
        dateOfBirth: this.dateOfBirth
      })
      .subscribe({
        next: (res) => {
          this.eligibilityResult = res;
          this.checkingEligibility = false;
        },
        error: (err) => {
          this.eligibilityError = err.error?.message ?? 'Eligibility check failed.';
          this.checkingEligibility = false;
        }
      });
  }

  lookupInvoice(): void {
    if (!this.invoiceId.trim()) return;
    this.lookingUpInvoice = true;
    this.invoiceError = '';
    this.invoice = null;
    this.billingService.getInvoiceById(this.invoiceId.trim()).subscribe({
      next: (res) => {
        this.invoice = res;
        this.paymentAmount = res.amount;
        this.lookingUpInvoice = false;
      },
      error: (err) => {
        this.invoiceError = err.error?.message ?? 'Invoice not found.';
        this.lookingUpInvoice = false;
      }
    });
  }

  pay(): void {
    if (!this.invoice || !this.paymentAmount) return;
    this.paying = true;
    this.paymentError = '';
    this.billingService
      .pay(this.invoice.id, { amount: this.paymentAmount, paymentMethod: this.paymentMethod })
      .subscribe({
        next: (res) => {
          this.invoice = res;
          this.paying = false;
        },
        error: (err) => {
          this.paymentError = err.error?.message ?? 'Payment failed.';
          this.paying = false;
        }
      });
  }
}
