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

export interface DecodedToken {
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
