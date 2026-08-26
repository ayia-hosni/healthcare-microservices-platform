import { Component, HostListener, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { AppointmentService } from '../../core/appointment.service';
import { DoctorService } from '../../core/doctor.service';
import { AppointmentResponse, DoctorResponse } from '../../core/models';

type SortCol = 'doctor' | 'date' | 'duration' | 'status';

@Component({
  selector: 'app-appointments',
  imports: [CommonModule, FormsModule],
  templateUrl: './appointments.component.html'
})
export class AppointmentsComponent implements OnInit {
  appointments: AppointmentResponse[] = [];
  loading = false;
  loadError = '';

  doctors: DoctorResponse[] = [];
  doctorsById = new Map<string, DoctorResponse>();

  searchQuery = '';
  statusFilter = 'All';
  sortCol: SortCol = 'date';
  sortAsc = false;

  currentPage = 1;
  rowsPerPage = 8;

  activeMenuId: string | null = null;

  // Create modal
  modalOpen = false;
  newDoctorId = '';
  newStart = '';
  newEnd = '';
  bookError = '';
  booking = false;

  // Cancel modal
  cancelTarget: AppointmentResponse | null = null;
  cancelReason = '';
  cancelling = false;
  cancelError = '';

  constructor(
    private auth: AuthService,
    private appointmentService: AppointmentService,
    private doctorService: DoctorService,
    private route: ActivatedRoute
  ) {}

  get patientId(): string {
    return this.auth.currentUser()?.sub ?? '';
  }

  ngOnInit(): void {
    const doctorIdParam = this.route.snapshot.queryParamMap.get('doctorId');
    if (doctorIdParam) {
      this.newDoctorId = doctorIdParam;
      this.modalOpen = true;
    }

    this.doctorService.findAll().subscribe({
      next: (res) => {
        this.doctors = res;
        this.doctorsById = new Map(res.map((d) => [d.id, d]));
      },
      error: () => {}
    });

    this.loadAppointments();
  }

  loadAppointments(): void {
    if (!this.patientId) return;
    this.loading = true;
    this.loadError = '';
    this.appointmentService.getByPatientId(this.patientId).subscribe({
      next: (res) => {
        this.appointments = res;
        this.loading = false;
      },
      error: (err) => {
        this.loadError = err.error?.message ?? 'Could not load your appointments.';
        this.loading = false;
      }
    });
  }

  doctorName(doctorId: string): string {
    const doctor = this.doctorsById.get(doctorId);
    return doctor ? `Dr. ${doctor.firstName} ${doctor.lastName}` : `${doctorId.slice(0, 8)}…`;
  }

  durationLabel(appt: AppointmentResponse): string {
    const ms = new Date(appt.scheduledEnd).getTime() - new Date(appt.scheduledStart).getTime();
    const minutes = Math.max(0, Math.round(ms / 60000));
    if (minutes < 60) return `${minutes}m`;
    const hours = Math.floor(minutes / 60);
    const rem = minutes % 60;
    return rem ? `${hours}h ${rem}m` : `${hours}h`;
  }

  get filtered(): AppointmentResponse[] {
    const query = this.searchQuery.trim().toLowerCase();
    return this.appointments.filter((a) => {
      const matchesStatus = this.statusFilter === 'All' || a.status === this.statusFilter;
      const matchesQuery = !query || this.doctorName(a.doctorId).toLowerCase().includes(query);
      return matchesStatus && matchesQuery;
    });
  }

  get sorted(): AppointmentResponse[] {
    const list = [...this.filtered];
    list.sort((a, b) => {
      let cmp = 0;
      switch (this.sortCol) {
        case 'doctor':
          cmp = this.doctorName(a.doctorId).localeCompare(this.doctorName(b.doctorId));
          break;
        case 'duration':
          cmp = this.durationLabel(a).localeCompare(this.durationLabel(b));
          break;
        case 'status':
          cmp = a.status.localeCompare(b.status);
          break;
        default:
          cmp = new Date(a.scheduledStart).getTime() - new Date(b.scheduledStart).getTime();
      }
      return this.sortAsc ? cmp : -cmp;
    });
    return list;
  }

  get totalPages(): number {
    return Math.ceil(this.sorted.length / this.rowsPerPage) || 1;
  }

  get paginated(): AppointmentResponse[] {
    if (this.currentPage > this.totalPages) this.currentPage = this.totalPages;
    const start = (this.currentPage - 1) * this.rowsPerPage;
    return this.sorted.slice(start, start + this.rowsPerPage);
  }

  onFilterChange(): void {
    this.currentPage = 1;
  }

  sortBy(col: SortCol): void {
    if (this.sortCol === col) {
      this.sortAsc = !this.sortAsc;
    } else {
      this.sortCol = col;
      this.sortAsc = true;
    }
  }

  prevPage(): void {
    if (this.currentPage > 1) this.currentPage--;
  }

  nextPage(): void {
    if (this.currentPage < this.totalPages) this.currentPage++;
  }

  toggleActionMenu(event: Event, id: string): void {
    event.stopPropagation();
    this.activeMenuId = this.activeMenuId === id ? null : id;
  }

  @HostListener('document:click')
  closeActionMenu(): void {
    this.activeMenuId = null;
  }

  // --- Create modal ---

  openModal(): void {
    this.bookError = '';
    this.newStart = '';
    this.newEnd = '';
    this.modalOpen = true;
  }

  closeModal(): void {
    this.modalOpen = false;
  }

  book(): void {
    this.bookError = '';
    this.booking = true;
    this.appointmentService
      .book({
        patientId: this.patientId,
        doctorId: this.newDoctorId,
        scheduledStart: new Date(this.newStart).toISOString(),
        scheduledEnd: new Date(this.newEnd).toISOString(),
        idempotencyKey: crypto.randomUUID()
      })
      .subscribe({
        next: () => {
          this.booking = false;
          this.modalOpen = false;
          this.loadAppointments();
        },
        error: (err) => {
          this.bookError = err.error?.message ?? 'Could not book appointment.';
          this.booking = false;
        }
      });
  }

  // --- Cancel modal ---

  openCancelModal(appt: AppointmentResponse): void {
    this.cancelTarget = appt;
    this.cancelReason = '';
    this.cancelError = '';
    this.activeMenuId = null;
  }

  closeCancelModal(): void {
    this.cancelTarget = null;
  }

  confirmCancel(): void {
    if (!this.cancelTarget) return;
    this.cancelling = true;
    this.cancelError = '';
    this.appointmentService.cancel(this.cancelTarget.id, this.cancelReason || 'Cancelled by patient').subscribe({
      next: () => {
        this.cancelling = false;
        this.cancelTarget = null;
        this.loadAppointments();
      },
      error: (err) => {
        this.cancelError = err.error?.message ?? 'Could not cancel appointment.';
        this.cancelling = false;
      }
    });
  }
}
