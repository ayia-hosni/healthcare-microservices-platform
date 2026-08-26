import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { AppointmentService } from '../../core/appointment.service';
import { DoctorService } from '../../core/doctor.service';
import { AppointmentResponse } from '../../core/models';

@Component({
  selector: 'app-dashboard',
  imports: [CommonModule, RouterLink],
  templateUrl: './dashboard.component.html'
})
export class DashboardComponent implements OnInit {
  appointments: AppointmentResponse[] = [];
  appointmentsLoading = true;
  doctorCount = 0;

  constructor(
    public auth: AuthService,
    private appointmentService: AppointmentService,
    private doctorService: DoctorService
  ) {}

  get patientId(): string {
    return this.auth.currentUser()?.sub ?? '';
  }

  get upcomingCount(): number {
    return this.appointments.filter((a) => a.status === 'SCHEDULED').length;
  }

  get recentAppointments(): AppointmentResponse[] {
    return [...this.appointments]
      .sort((a, b) => new Date(b.scheduledStart).getTime() - new Date(a.scheduledStart).getTime())
      .slice(0, 5);
  }

  ngOnInit(): void {
    if (this.patientId) {
      this.appointmentService.getByPatientId(this.patientId).subscribe({
        next: (res) => {
          this.appointments = res;
          this.appointmentsLoading = false;
        },
        error: () => {
          this.appointmentsLoading = false;
        }
      });
    } else {
      this.appointmentsLoading = false;
    }

    this.doctorService.findAll().subscribe({
      next: (res) => (this.doctorCount = res.length),
      error: () => {}
    });
  }

  statusDotClass(status: string): string {
    switch (status) {
      case 'COMPLETED':
        return 'activity-dot completed';
      case 'CANCELLED':
        return 'activity-dot cancelled';
      case 'RESCHEDULED':
        return 'activity-dot rescheduled';
      default:
        return 'activity-dot';
    }
  }
}
