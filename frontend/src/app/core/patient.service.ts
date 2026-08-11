import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { PatientRequest, PatientResponse } from './models';

@Injectable({ providedIn: 'root' })
export class PatientService {
  constructor(private http: HttpClient) {}

  register(request: PatientRequest): Observable<PatientResponse> {
    return this.http.post<PatientResponse>('/api/v1/patients', request);
  }

  getById(id: string): Observable<PatientResponse> {
    return this.http.get<PatientResponse>(`/api/v1/patients/${id}`);
  }

  search(query: string): Observable<PatientResponse[]> {
    return this.http.get<PatientResponse[]>('/api/v1/patients/search', { params: { query } });
  }

  updatePhone(id: string, phoneNumber: string): Observable<PatientResponse> {
    return this.http.patch<PatientResponse>(`/api/v1/patients/${id}/phone`, null, {
      params: { phoneNumber }
    });
  }
}
