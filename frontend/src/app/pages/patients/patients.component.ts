import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { PatientService } from '../../core/patient.service';
import { AppointmentService } from '../../core/appointment.service';
import { AuthService } from '../../core/auth.service';
import { PatientResponse } from '../../core/models';

@Component({
  selector: 'app-patients',
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './patients.component.html'
})
export class PatientsComponent implements OnInit {
  query = '';
  results: PatientResponse[] = [];
  loading = false;
  searched = false;
  error = '';

  // A DOCTOR (not ADMIN/NURSE) only ever sees patients they actually have an appointment
  // with — patient-service has no per-doctor access control of its own (search is open to
  // any ADMIN/DOCTOR/NURSE), so this is enforced here by filtering search results down to
  // patientIds pulled from the doctor's own appointment list.
  ownPatientIds: Set<string> | null = null;

  constructor(private patientService: PatientService, private appointmentService: AppointmentService, private auth: AuthService) {}

  get isDoctorView(): boolean {
    const roles = this.auth.currentUser()?.roles ?? [];
    return roles.includes('ROLE_DOCTOR') && !roles.includes('ROLE_ADMIN');
  }

  ngOnInit(): void {
    if (!this.isDoctorView) return;
    const doctorId = this.auth.currentUser()?.sub ?? '';
    this.appointmentService.getByDoctorId(doctorId).subscribe({
      next: (res) => {
        this.ownPatientIds = new Set(res.map((a) => a.patientId));
      },
      error: () => {
        this.ownPatientIds = new Set();
      }
    });
  }

  search(): void {
    if (!this.query.trim()) return;
    this.loading = true;
    this.searched = true;
    this.error = '';
    this.patientService.search(this.query.trim()).subscribe({
      next: (res) => {
        this.results = this.isDoctorView && this.ownPatientIds ? res.filter((p) => this.ownPatientIds!.has(p.id)) : res;
        this.loading = false;
      },
      error: (err) => {
        this.error =
          err.status === 403
            ? 'Your account does not have permission to search patients (staff-only feature).'
            : err.error?.message ?? 'Search failed.';
        this.results = [];
        this.loading = false;
      }
    });
  }

  initials(patient: PatientResponse): string {
    return `${patient.firstName[0] ?? ''}${patient.lastName[0] ?? ''}`.toUpperCase();
  }
}
