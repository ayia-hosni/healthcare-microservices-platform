import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { AppointmentService } from '../../core/appointment.service';
import { AppointmentResponse } from '../../core/models';

@Component({
  selector: 'app-appointments',
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './appointments.component.html'
})
export class AppointmentsComponent {
  doctorId = '';
  scheduledStart = '';
  scheduledEnd = '';
  bookError = '';
  booking = false;
  booked: AppointmentResponse | null = null;

  lookupId = '';
  lookupError = '';
  lookupLoading = false;
  found: AppointmentResponse | null = null;

  cancelReason = '';
  cancelling = false;

  constructor(private auth: AuthService, private appointmentService: AppointmentService) {}

  get patientId(): string {
    return this.auth.currentUser()?.sub ?? '';
  }

  book(): void {
    this.bookError = '';
    this.booking = true;
    this.appointmentService
      .book({
        patientId: this.patientId,
        doctorId: this.doctorId.trim(),
        scheduledStart: new Date(this.scheduledStart).toISOString(),
        scheduledEnd: new Date(this.scheduledEnd).toISOString(),
        idempotencyKey: crypto.randomUUID()
      })
      .subscribe({
        next: (res) => {
          this.booked = res;
          this.booking = false;
        },
        error: (err) => {
          this.bookError = err.error?.message ?? 'Could not book appointment.';
          this.booking = false;
        }
      });
  }

  lookup(): void {
    if (!this.lookupId.trim()) return;
    this.lookupError = '';
    this.lookupLoading = true;
    this.found = null;
    this.appointmentService.getById(this.lookupId.trim()).subscribe({
      next: (res) => {
        this.found = res;
        this.lookupLoading = false;
      },
      error: (err) => {
        this.lookupError = err.error?.message ?? 'Appointment not found.';
        this.lookupLoading = false;
      }
    });
  }

  cancel(): void {
    if (!this.found) return;
    this.cancelling = true;
    this.appointmentService.cancel(this.found.id, this.cancelReason || 'Cancelled by patient').subscribe({
      next: (res) => {
        this.found = res;
        this.cancelling = false;
      },
      error: (err) => {
        this.lookupError = err.error?.message ?? 'Could not cancel appointment.';
        this.cancelling = false;
      }
    });
  }
}
