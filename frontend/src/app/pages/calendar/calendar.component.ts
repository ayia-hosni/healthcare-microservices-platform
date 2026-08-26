import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { AuthService } from '../../core/auth.service';
import { AppointmentService } from '../../core/appointment.service';
import { DoctorService } from '../../core/doctor.service';
import { AppointmentResponse, DoctorResponse } from '../../core/models';

interface CalendarCell {
  day: number | null;
  isToday: boolean;
  appointments: AppointmentResponse[];
}

@Component({
  selector: 'app-calendar',
  imports: [CommonModule, FormsModule],
  templateUrl: './calendar.component.html'
})
export class CalendarComponent implements OnInit {
  appointments: AppointmentResponse[] = [];
  doctorsById = new Map<string, DoctorResponse>();
  loading = true;
  loadError = '';

  viewDate = new Date();
  cells: CalendarCell[] = [];
  weekdays = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];

  selected: AppointmentResponse | null = null;
  cancelReason = '';
  cancelling = false;
  cancelError = '';

  constructor(
    private auth: AuthService,
    private appointmentService: AppointmentService,
    private doctorService: DoctorService
  ) {}

  get patientId(): string {
    return this.auth.currentUser()?.sub ?? '';
  }

  get monthLabel(): string {
    return this.viewDate.toLocaleDateString('en-US', { month: 'long', year: 'numeric' });
  }

  ngOnInit(): void {
    this.doctorService.findAll().subscribe({
      next: (res) => (this.doctorsById = new Map(res.map((d) => [d.id, d]))),
      error: () => {}
    });

    if (!this.patientId) {
      this.loading = false;
      return;
    }

    this.appointmentService.getByPatientId(this.patientId).subscribe({
      next: (res) => {
        this.appointments = res;
        this.loading = false;
        this.buildGrid();
      },
      error: (err) => {
        this.loadError = err.error?.message ?? 'Could not load appointments.';
        this.loading = false;
        this.buildGrid();
      }
    });

    this.buildGrid();
  }

  doctorName(doctorId: string): string {
    const doctor = this.doctorsById.get(doctorId);
    return doctor ? `Dr. ${doctor.firstName} ${doctor.lastName}` : `${doctorId.slice(0, 8)}…`;
  }

  buildGrid(): void {
    const year = this.viewDate.getFullYear();
    const month = this.viewDate.getMonth();
    const firstDay = new Date(year, month, 1).getDay();
    const daysInMonth = new Date(year, month + 1, 0).getDate();
    const today = new Date();

    const cells: CalendarCell[] = [];
    for (let i = 0; i < firstDay; i++) {
      cells.push({ day: null, isToday: false, appointments: [] });
    }
    for (let day = 1; day <= daysInMonth; day++) {
      const dayAppointments = this.appointments.filter((a) => {
        const d = new Date(a.scheduledStart);
        return d.getFullYear() === year && d.getMonth() === month && d.getDate() === day;
      });
      const isToday =
        today.getFullYear() === year && today.getMonth() === month && today.getDate() === day;
      cells.push({ day, isToday, appointments: dayAppointments });
    }
    this.cells = cells;
  }

  prevMonth(): void {
    this.viewDate = new Date(this.viewDate.getFullYear(), this.viewDate.getMonth() - 1, 1);
    this.buildGrid();
  }

  nextMonth(): void {
    this.viewDate = new Date(this.viewDate.getFullYear(), this.viewDate.getMonth() + 1, 1);
    this.buildGrid();
  }

  eventClass(appt: AppointmentResponse): string {
    if (appt.status === 'CANCELLED') return 'fc-event cancelled';
    if (appt.status === 'COMPLETED') return 'fc-event completed';
    return 'fc-event';
  }

  selectAppointment(appt: AppointmentResponse): void {
    this.selected = appt;
    this.cancelReason = '';
    this.cancelError = '';
  }

  closeDetail(): void {
    this.selected = null;
  }

  confirmCancel(): void {
    if (!this.selected) return;
    this.cancelling = true;
    this.cancelError = '';
    this.appointmentService.cancel(this.selected.id, this.cancelReason || 'Cancelled by patient').subscribe({
      next: (res) => {
        this.cancelling = false;
        this.selected = res;
        const idx = this.appointments.findIndex((a) => a.id === res.id);
        if (idx !== -1) this.appointments[idx] = res;
        this.buildGrid();
      },
      error: (err) => {
        this.cancelError = err.error?.message ?? 'Could not cancel appointment.';
        this.cancelling = false;
      }
    });
  }
}
