export interface RegisterRequest {
  email: string;
  password: string;
}

export interface LoginRequest {
  email: string;
  password: string;
}

export interface AuthResponse {
  accessToken: string;
  refreshToken: string;
  accessTokenExpiresInSeconds: number;
}

export type StaffRole = 'ROLE_ADMIN' | 'ROLE_DOCTOR' | 'ROLE_NURSE' | 'ROLE_BILLING_CLERK';

export interface CreateStaffAccountRequest {
  email: string;
  password: string;
  role: StaffRole;
}

export interface StaffAccountResponse {
  id: string;
  email: string;
  role: StaffRole;
}

export interface DecodedToken {
  /** OpenID Connect issuer — must match environment.authIssuerUri or the token is rejected. */
  iss: string;
  sub: string;
  email: string;
  roles: string[];
  iat: number;
  exp: number;
}

export interface PatientRequest {
  id: string;
  firstName: string;
  lastName: string;
  dateOfBirth: string;
  email: string;
  phoneNumber?: string;
}

export interface PatientResponse {
  id: string;
  firstName: string;
  lastName: string;
  dateOfBirth: string;
  email: string;
  phoneNumber?: string;
}

export interface DoctorRequest {
  id: string;
  firstName: string;
  lastName: string;
  specialty: string;
  departmentName: string;
}

export interface DoctorResponse {
  id: string;
  firstName: string;
  lastName: string;
  specialty: string;
  departmentName: string;
}

export interface AvailabilitySlotResponse {
  id: string;
  dayOfWeek: string; // java.time.DayOfWeek name, e.g. "MONDAY" — all times UTC
  startTime: string; // "HH:mm:ss"
  endTime: string;
}

export interface AvailabilitySlotRequest {
  dayOfWeek: string;
  startTime: string;
  endTime: string;
}

export type AppointmentStatus = 'SCHEDULED' | 'CANCELLED' | 'COMPLETED' | 'RESCHEDULED' | string;

export interface BookAppointmentRequest {
  patientId: string;
  doctorId: string;
  scheduledStart: string;
  scheduledEnd: string;
  idempotencyKey: string;
}

export interface AppointmentResponse {
  id: string;
  patientId: string;
  doctorId: string;
  scheduledStart: string;
  scheduledEnd: string;
  status: AppointmentStatus;
}

export type InvoiceStatus = 'PENDING' | 'PAID' | 'PARTIALLY_PAID' | 'OVERDUE' | 'CANCELLED' | string;

export interface InvoiceResponse {
  id: string;
  patientId: string;
  appointmentId: string;
  amount: number;
  currency: string;
  status: InvoiceStatus;
  dueDate: string;
}

export interface PaymentRequest {
  amount: number;
  paymentMethod: string;
}

export interface EligibilityRequest {
  memberId: string;
  payerId: string;
  dateOfBirth: string;
}

export interface EligibilityResponse {
  eligible: boolean;
  planName: string;
  copayAmount: number;
  payerMessage: string;
}
