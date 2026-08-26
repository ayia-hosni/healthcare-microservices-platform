import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { PatientService } from '../../core/patient.service';
import { PatientResponse } from '../../core/models';

@Component({
  selector: 'app-settings',
  imports: [CommonModule, RouterLink],
  templateUrl: './settings.component.html'
})
export class SettingsComponent implements OnInit {
  profile: PatientResponse | null = null;
  loading = true;

  constructor(public auth: AuthService, private patientService: PatientService) {}

  get userId(): string {
    return this.auth.currentUser()?.sub ?? '';
  }

  ngOnInit(): void {
    if (!this.userId) {
      this.loading = false;
      return;
    }
    this.patientService.getById(this.userId).subscribe({
      next: (res) => {
        this.profile = res;
        this.loading = false;
      },
      error: () => {
        this.loading = false;
      }
    });
  }
}
