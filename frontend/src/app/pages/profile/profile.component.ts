import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { AuthService } from '../../core/auth.service';
import { PatientService } from '../../core/patient.service';
import { PatientResponse } from '../../core/models';

@Component({
  selector: 'app-profile',
  imports: [CommonModule, FormsModule],
  templateUrl: './profile.component.html'
})
export class ProfileComponent implements OnInit {
  profile: PatientResponse | null = null;
  loading = false;
  error = '';
  notFound = false;

  firstName = '';
  lastName = '';
  dateOfBirth = '';
  phoneNumber = '';

  newPhoneNumber = '';
  savingPhone = false;

  constructor(private auth: AuthService, private patientService: PatientService) {}

  get userId(): string {
    return this.auth.currentUser()?.sub ?? '';
  }

  get email(): string {
    return this.auth.currentUser()?.email ?? '';
  }

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading = true;
    this.error = '';
    this.patientService.getById(this.userId).subscribe({
      next: (res) => {
        this.profile = res;
        this.newPhoneNumber = res.phoneNumber ?? '';
        this.loading = false;
      },
      error: (err) => {
        this.loading = false;
        if (err.status === 404) {
          this.notFound = true;
        } else {
          this.error = err.error?.message ?? 'Could not load profile.';
        }
      }
    });
  }

  createProfile(): void {
    this.error = '';
    this.loading = true;
    this.patientService
      .register({
        id: this.userId,
        firstName: this.firstName,
        lastName: this.lastName,
        dateOfBirth: this.dateOfBirth,
        email: this.email,
        phoneNumber: this.phoneNumber || undefined
      })
      .subscribe({
        next: (res) => {
          this.profile = res;
          this.notFound = false;
          this.loading = false;
        },
        error: (err) => {
          this.loading = false;
          this.error = err.error?.message ?? 'Could not create profile.';
        }
      });
  }

  updatePhone(): void {
    if (!this.profile) return;
    this.savingPhone = true;
    this.patientService.updatePhone(this.profile.id, this.newPhoneNumber).subscribe({
      next: (res) => {
        this.profile = res;
        this.savingPhone = false;
      },
      error: (err) => {
        this.savingPhone = false;
        this.error = err.error?.message ?? 'Could not update phone number.';
      }
    });
  }
}
