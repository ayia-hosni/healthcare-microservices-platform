import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
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
  selector: 'app-admin-doctor-schedule',
  imports: [CommonModule, FormsModule],
  templateUrl: './admin-doctor-schedule.component.html'
})
export class AdminDoctorScheduleComponent implements OnInit {
  days = DAYS;

  doctors: DoctorResponse[] = [];
  loadingDoctors = false;
  doctorsError = '';
  selectedDoctorId = '';

  loadingSlots = false;
  slotsError = '';
  slots: AvailabilitySlotResponse[] = [];

  dayOfWeek = 'MONDAY';
  startTime = '09:00';
  endTime = '17:00';
  addingSlot = false;
  slotError = '';
  removingId: string | null = null;

  constructor(private doctorService: DoctorService, public auth: AuthService) {}

  /** A DOCTOR (not ADMIN) can only ever manage their own hours — backend enforces this too
   *  (DoctorController: hasRole('ADMIN') or #id.toString() == authentication.name), this just
   *  keeps the UI from offering a dropdown of doctors whose slots they'd just get a 403 on. */
  get isRestrictedToSelf(): boolean {
    const roles = this.auth.currentUser()?.roles ?? [];
    return roles.includes('ROLE_DOCTOR') && !roles.includes('ROLE_ADMIN');
  }

  ngOnInit(): void {
    this.loadingDoctors = true;
    this.doctorService.findAll().subscribe({
      next: (res) => {
        this.doctors = res;
        this.loadingDoctors = false;

        if (this.isRestrictedToSelf) {
          const ownId = this.auth.currentUser()?.sub ?? '';
          this.selectedDoctorId = ownId;
          this.onDoctorChange();
        }
      },
      error: (err) => {
        this.doctorsError = err.error?.message ?? 'Could not load doctors.';
        this.loadingDoctors = false;
      }
    });
  }

  get selectedDoctor(): DoctorResponse | undefined {
    return this.doctors.find((d) => d.id === this.selectedDoctorId);
  }

  dayLabel(value: string): string {
    return this.days.find((d) => d.value === value)?.label ?? value;
  }

  onDoctorChange(): void {
    this.slots = [];
    this.slotsError = '';
    this.slotError = '';
    if (!this.selectedDoctorId) return;

    this.loadingSlots = true;
    this.doctorService.getAvailability(this.selectedDoctorId).subscribe({
      next: (res) => {
        this.slots = [...res].sort(
          (a, b) => DAY_ORDER.indexOf(a.dayOfWeek) - DAY_ORDER.indexOf(b.dayOfWeek) || a.startTime.localeCompare(b.startTime)
        );
        this.loadingSlots = false;
      },
      error: (err) => {
        this.slotsError = err.error?.message ?? "Could not load this doctor's schedule.";
        this.loadingSlots = false;
      }
    });
  }

  addSlot(): void {
    if (!this.selectedDoctorId) return;
    this.slotError = '';

    if (this.startTime >= this.endTime) {
      this.slotError = 'Start time must be before end time.';
      return;
    }

    this.addingSlot = true;
    this.doctorService
      .addAvailabilitySlot(this.selectedDoctorId, { dayOfWeek: this.dayOfWeek, startTime: this.startTime, endTime: this.endTime })
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
    if (!this.selectedDoctorId) return;
    this.removingId = slot.id;
    this.doctorService.removeAvailabilitySlot(this.selectedDoctorId, slot.id).subscribe({
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
