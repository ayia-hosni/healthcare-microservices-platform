import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { AppointmentResponse, BookAppointmentRequest } from './models';

@Injectable({ providedIn: 'root' })
export class AppointmentService {
  constructor(private http: HttpClient) {}

  book(request: BookAppointmentRequest): Observable<AppointmentResponse> {
    return this.http.post<AppointmentResponse>('/api/v1/appointments', request);
  }

  getById(id: string): Observable<AppointmentResponse> {
    return this.http.get<AppointmentResponse>(`/api/v1/appointments/${id}`);
  }

  getByPatientId(patientId: string): Observable<AppointmentResponse[]> {
    return this.http.get<AppointmentResponse[]>('/api/v1/appointments', { params: { patientId } });
  }

  cancel(id: string, reason: string): Observable<AppointmentResponse> {
    return this.http.post<AppointmentResponse>(`/api/v1/appointments/${id}/cancel`, { reason });
  }

  reschedule(id: string, newStart: string, newEnd: string): Observable<AppointmentResponse> {
    return this.http.post<AppointmentResponse>(`/api/v1/appointments/${id}/reschedule`, {
      newStart,
      newEnd
    });
  }
}
