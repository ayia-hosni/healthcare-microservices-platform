import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { AuthService } from '../../core/auth.service';
import { StaffAccountResponse, StaffRole } from '../../core/models';

@Component({
  selector: 'app-admin-staff',
  imports: [CommonModule, FormsModule],
  templateUrl: './admin-staff.component.html'
})
export class AdminStaffComponent {
  roles: { value: StaffRole; label: string }[] = [
    { value: 'ROLE_ADMIN', label: 'Admin' },
    { value: 'ROLE_DOCTOR', label: 'Doctor' },
    { value: 'ROLE_NURSE', label: 'Nurse' },
    { value: 'ROLE_BILLING_CLERK', label: 'Billing clerk' }
  ];

  email = '';
  password = '';
  role: StaffRole = 'ROLE_DOCTOR';

  creating = false;
  createError = '';

  // Session-local record of what this admin created — the platform has no "list all staff"
  // endpoint yet, so this is a receipt, not a directory.
  created: StaffAccountResponse[] = [];

  constructor(private auth: AuthService) {}

  roleLabel(role: StaffRole): string {
    return this.roles.find((r) => r.value === role)?.label ?? role;
  }

  submit(): void {
    this.createError = '';
    this.creating = true;
    this.auth.createStaffAccount({ email: this.email, password: this.password, role: this.role }).subscribe({
      next: (res) => {
        this.created.unshift(res);
        this.email = '';
        this.password = '';
        this.creating = false;
      },
      error: (err) => {
        this.createError = err.error?.message ?? 'Could not create this account.';
        this.creating = false;
      }
    });
  }
}
