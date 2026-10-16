import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { DoctorService } from '../../core/doctor.service';
import { AuthService } from '../../core/auth.service';
import { AvailabilitySlotResponse, DoctorResponse } from '../../core/models';

const DAYS: { value: string; label: string }[] = [
  { value: 'MONDAY', label: 'Monday' },
  { value: 'TUESDAY', label: 'Tuesday' },
  { value: 'WEDNESDAY', label: 'Wednesday' },
  { value: 'THURSDAY', label: 'Thursday' },
  { value: 'FRIDAY', label: 'Friday' },
  { value: 'SATURDAY', label: 'Saturday' },
  { value: 'SUNDAY', label: 'Sunday' }
];

const DAY_ORDER = DAYS.map((d) => d.value);

@Component({
  selector: 'app-admin-doctor-create',
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './admin-doctor-create.component.html'
})
export class AdminDoctorCreateComponent {
  days = DAYS;

  // Step 1: login + profile, created together so Doctor.id == the login's own identity-service
  // user id (the same shared-id convention patients already use) — that link is what lets a
  // DOCTOR-role user manage only their own schedule/appointments later.
  email = '';
  password = '';
  firstName = '';
  lastName = '';
  specialty = '';
  departmentName = '';
  creating = false;
  createError = '';
  doctor: DoctorResponse | null = null;

  // Step 2: weekly schedule (only meaningful once `doctor` exists)
  dayOfWeek = 'MONDAY';
  startTime = '09:00';
  endTime = '17:00';
  addingSlot = false;
  slotError = '';
  removingId: string | null = null;
  slots: AvailabilitySlotResponse[] = [];

  constructor(private doctorService: DoctorService, private authService: AuthService) {}

  dayLabel(value: string): string {
    return this.days.find((d) => d.value === value)?.label ?? value;
  }

  createDoctor(): void {
    this.createError = '';
    this.creating = true;

    this.authService.createStaffAccount({ email: this.email.trim(), password: this.password, role: 'ROLE_DOCTOR' }).subscribe({
      next: (account) => {
        this.doctorService
          .create({
            id: account.id,
            firstName: this.firstName.trim(),
            lastName: this.lastName.trim(),
            specialty: this.specialty.trim(),
            departmentName: this.departmentName.trim()
          })
          .subscribe({
            next: (res) => {
              this.doctor = res;
              this.creating = false;
            },
            error: (err) => {
              // The login account was already created at this point (can't roll that back from
              // here) — say so plainly rather than implying nothing happened, since retrying
              // with the same email would now 409.
              this.createError =
                (err.error?.message ?? 'Could not create the doctor profile.') +
                ` The login ${this.email.trim()} was already created — use a different email if you retry, or add the profile for this account separately.`;
              this.creating = false;
            }
          });
      },
      error: (err) => {
        this.createError = err.error?.message ?? 'Could not create the login account.';
        this.creating = false;
      }
    });
  }

  addSlot(): void {
    if (!this.doctor) return;
    this.slotError = '';

    if (this.startTime >= this.endTime) {
      this.slotError = 'Start time must be before end time.';
      return;
    }

    this.addingSlot = true;
    this.doctorService
      .addAvailabilitySlot(this.doctor.id, { dayOfWeek: this.dayOfWeek, startTime: this.startTime, endTime: this.endTime })
      .subscribe({
        next: (res) => {
          this.slots = [...this.slots, res].sort(
            (a, b) => DAY_ORDER.indexOf(a.dayOfWeek) - DAY_ORDER.indexOf(b.dayOfWeek) || a.startTime.localeCompare(b.startTime)
          );
          this.addingSlot = false;
        },
        error: (err) => {
          this.slotError = err.error?.message ?? 'Could not add this slot.';
          this.addingSlot = false;
        }
      });
  }

  removeSlot(slot: AvailabilitySlotResponse): void {
    if (!this.doctor) return;
    this.removingId = slot.id;
    this.doctorService.removeAvailabilitySlot(this.doctor.id, slot.id).subscribe({
      next: () => {
        this.slots = this.slots.filter((s) => s.id !== slot.id);
        this.removingId = null;
      },
      error: () => {
        this.removingId = null;
      }
    });
  }
}
