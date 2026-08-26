import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { PatientService } from '../../core/patient.service';

@Component({
  selector: 'app-register',
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './register.component.html'
})
export class RegisterComponent {
  email = '';
  password = '';
  firstName = '';
  lastName = '';
  dateOfBirth = '';
  phoneNumber = '';
  error = '';
  loading = false;

  constructor(
    private auth: AuthService,
    private patientService: PatientService,
    private router: Router
  ) {}

  submit(): void {
    this.error = '';
    this.loading = true;
    this.auth.register({ email: this.email, password: this.password }).subscribe({
      next: () => {
        const patientId = this.auth.currentUser()?.sub;
        if (!patientId) {
          // Account was created but we couldn't read the new user id back out of the token —
          // land on /profile anyway, which offers its own "create profile" fallback form.
          this.loading = false;
          this.router.navigateByUrl('/profile');
          return;
        }
        this.patientService
          .register({
            id: patientId,
            firstName: this.firstName,
            lastName: this.lastName,
            dateOfBirth: this.dateOfBirth,
            email: this.email,
            phoneNumber: this.phoneNumber || undefined
          })
          .subscribe({
            // Whether the patient record was created here or (on failure) via /profile's own
            // fallback form, either way the account itself already exists — always proceed.
            next: () => {
              this.loading = false;
              this.router.navigateByUrl('/profile');
            },
            error: () => {
              this.loading = false;
              this.router.navigateByUrl('/profile');
            }
          });
      },
      error: (err) => {
        this.loading = false;
        this.error = err.error?.message ?? 'Registration failed.';
      }
    });
  }
}
