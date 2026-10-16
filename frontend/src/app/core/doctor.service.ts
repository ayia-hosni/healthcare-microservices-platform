import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { AvailabilitySlotRequest, AvailabilitySlotResponse, DoctorRequest, DoctorResponse } from './models';

@Injectable({ providedIn: 'root' })
export class DoctorService {
  constructor(private http: HttpClient) {}

  findAll(): Observable<DoctorResponse[]> {
    return this.http.get<DoctorResponse[]>('/api/v1/doctors');
  }

  getById(id: string): Observable<DoctorResponse> {
    return this.http.get<DoctorResponse>(`/api/v1/doctors/${id}`);
  }

  findBySpecialty(specialty: string): Observable<DoctorResponse[]> {
    return this.http.get<DoctorResponse[]>('/api/v1/doctors/search', { params: { specialty } });
  }

  create(request: DoctorRequest): Observable<DoctorResponse> {
    return this.http.post<DoctorResponse>('/api/v1/doctors', request);
  }

  getAvailability(doctorId: string): Observable<AvailabilitySlotResponse[]> {
    return this.http.get<AvailabilitySlotResponse[]>(`/api/v1/doctors/${doctorId}/availability`);
  }

  addAvailabilitySlot(doctorId: string, request: AvailabilitySlotRequest): Observable<AvailabilitySlotResponse> {
    return this.http.post<AvailabilitySlotResponse>(`/api/v1/doctors/${doctorId}/availability`, request);
  }

  removeAvailabilitySlot(doctorId: string, slotId: string): Observable<void> {
    return this.http.delete<void>(`/api/v1/doctors/${doctorId}/availability/${slotId}`);
  }
}
