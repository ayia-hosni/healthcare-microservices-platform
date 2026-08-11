import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { DoctorResponse } from './models';

@Injectable({ providedIn: 'root' })
export class DoctorService {
  constructor(private http: HttpClient) {}

  getById(id: string): Observable<DoctorResponse> {
    return this.http.get<DoctorResponse>(`/api/v1/doctors/${id}`);
  }

  findBySpecialty(specialty: string): Observable<DoctorResponse[]> {
    return this.http.get<DoctorResponse[]>('/api/v1/doctors/search', { params: { specialty } });
  }
}
