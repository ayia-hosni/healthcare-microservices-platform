import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { PatientService } from '../../core/patient.service';
import { PatientResponse } from '../../core/models';

@Component({
  selector: 'app-patients',
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './patients.component.html'
})
export class PatientsComponent {
  query = '';
  results: PatientResponse[] = [];
  loading = false;
  searched = false;
  error = '';

  constructor(private patientService: PatientService) {}

  search(): void {
    if (!this.query.trim()) return;
    this.loading = true;
    this.searched = true;
    this.error = '';
    this.patientService.search(this.query.trim()).subscribe({
      next: (res) => {
        this.results = res;
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
