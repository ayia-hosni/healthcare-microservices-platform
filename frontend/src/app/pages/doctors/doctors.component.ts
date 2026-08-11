import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { DoctorService } from '../../core/doctor.service';
import { DoctorResponse } from '../../core/models';

@Component({
  selector: 'app-doctors',
  imports: [CommonModule, FormsModule],
  templateUrl: './doctors.component.html'
})
export class DoctorsComponent {
  specialty = '';
  results: DoctorResponse[] = [];
  error = '';
  loading = false;
  searched = false;

  constructor(private doctorService: DoctorService) {}

  search(): void {
    if (!this.specialty.trim()) return;
    this.error = '';
    this.loading = true;
    this.searched = true;
    this.doctorService.findBySpecialty(this.specialty.trim()).subscribe({
      next: (res) => {
        this.results = res;
        this.loading = false;
      },
      error: (err) => {
        this.error = err.error?.message ?? 'Search failed.';
        this.results = [];
        this.loading = false;
      }
    });
  }
}
