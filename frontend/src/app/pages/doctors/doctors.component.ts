import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { DoctorService } from '../../core/doctor.service';
import { DoctorResponse } from '../../core/models';

@Component({
  selector: 'app-doctors',
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './doctors.component.html'
})
export class DoctorsComponent implements OnInit {
  specialty = '';
  results: DoctorResponse[] = [];
  error = '';
  loading = false;
  searched = false;

  constructor(private doctorService: DoctorService) {}

  initials(doctor: DoctorResponse): string {
    return `${doctor.firstName[0] ?? ''}${doctor.lastName[0] ?? ''}`.toUpperCase();
  }

  ngOnInit(): void {
    this.browseAll();
  }

  browseAll(): void {
    this.specialty = '';
    this.error = '';
    this.loading = true;
    this.searched = false;
    this.doctorService.findAll().subscribe({
      next: (res) => {
        this.results = res;
        this.loading = false;
      },
      error: (err) => {
        this.error = err.error?.message ?? 'Could not load doctors.';
        this.results = [];
        this.loading = false;
      }
    });
  }

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
