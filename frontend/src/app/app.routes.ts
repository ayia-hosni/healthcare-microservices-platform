import { Routes } from '@angular/router';
import { authGuard } from './core/auth.guard';
import { adminGuard } from './core/admin.guard';
import { staffGuard } from './core/staff.guard';
import { LoginComponent } from './pages/login/login.component';
import { RegisterComponent } from './pages/register/register.component';
import { DashboardComponent } from './pages/dashboard/dashboard.component';
import { DoctorsComponent } from './pages/doctors/doctors.component';
import { ProfileComponent } from './pages/profile/profile.component';
import { AppointmentsComponent } from './pages/appointments/appointments.component';
import { CalendarComponent } from './pages/calendar/calendar.component';
import { PatientsComponent } from './pages/patients/patients.component';
import { BillingComponent } from './pages/billing/billing.component';
import { SettingsComponent } from './pages/settings/settings.component';
import { SupportComponent } from './pages/support/support.component';
import { AdminDashboardComponent } from './pages/admin/admin-dashboard.component';
import { AdminStaffComponent } from './pages/admin/admin-staff.component';
import { AdminDoctorCreateComponent } from './pages/admin/admin-doctor-create.component';
import { AdminDoctorScheduleComponent } from './pages/admin/admin-doctor-schedule.component';

export const routes: Routes = [
  { path: 'login', component: LoginComponent },
  { path: 'register', component: RegisterComponent },
  { path: '', component: DashboardComponent, canActivate: [authGuard] },
  { path: 'calendar', component: CalendarComponent, canActivate: [authGuard] },
  { path: 'doctors', component: DoctorsComponent, canActivate: [authGuard] },
  { path: 'patients', component: PatientsComponent, canActivate: [authGuard] },
  { path: 'profile', component: ProfileComponent, canActivate: [authGuard] },
  { path: 'appointments', component: AppointmentsComponent, canActivate: [authGuard] },
  { path: 'billing', component: BillingComponent, canActivate: [authGuard] },
  { path: 'settings', component: SettingsComponent, canActivate: [authGuard] },
  { path: 'support', component: SupportComponent, canActivate: [authGuard] },
  { path: 'admin', component: AdminDashboardComponent, canActivate: [adminGuard] },
  { path: 'admin/staff', component: AdminStaffComponent, canActivate: [adminGuard] },
  { path: 'admin/doctors/create', component: AdminDoctorCreateComponent, canActivate: [adminGuard] },
  { path: 'admin/doctors/schedule', component: AdminDoctorScheduleComponent, canActivate: [staffGuard] },
  { path: '**', redirectTo: '' }
];
