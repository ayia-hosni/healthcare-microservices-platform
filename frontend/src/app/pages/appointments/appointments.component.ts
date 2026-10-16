import { Component, HostListener, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { AppointmentService } from '../../core/appointment.service';
import { DoctorService } from '../../core/doctor.service';
import { AppointmentResponse, AvailabilitySlotResponse, DoctorResponse } from '../../core/models';

type SortCol = 'doctor' | 'date' | 'duration' | 'status';

const DAY_OF_WEEK = ['SUNDAY', 'MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY'];
const DAY_LABEL = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
const MONTH_LABEL = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const SLOT_MINUTES = 30;

interface DayCell {
  date: Date;
  dayName: string;
  dayNum: number;
  isToday: boolean;
  slotLabels: string[];
}

interface SlotGroup {
  label: string;
  times: string[];
}

/** Monday-of-week for `d`, expressed as a UTC midnight Date (so week math never drifts across DST). */
function mondayOf(d: Date): Date {
  const utcDay = new Date(Date.UTC(d.getFullYear(), d.getMonth(), d.getDate()));
  const dow = utcDay.getUTCDay(); // 0=Sun..6=Sat
  const diff = (dow === 0 ? -6 : 1) - dow;
  utcDay.setUTCDate(utcDay.getUTCDate() + diff);
  return utcDay;
}

function addUtcDays(d: Date, n: number): Date {
  const r = new Date(d);
  r.setUTCDate(r.getUTCDate() + n);
  return r;
}

function sameUtcDay(a: Date, b: Date): boolean {
  return a.getUTCFullYear() === b.getUTCFullYear() && a.getUTCMonth() === b.getUTCMonth() && a.getUTCDate() === b.getUTCDate();
}

/** "09:00:00" / "09:00" -> minutes since midnight. */
function timeToMinutes(time: string): number {
  const [h, m] = time.split(':').map(Number);
  return h * 60 + m;
}

function minutesToLabel(mins: number): string {
  const h = Math.floor(mins / 60);
  const m = mins % 60;
  return `${h < 10 ? '0' : ''}${h}:${m < 10 ? '0' : ''}${m}`;
}

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
  bookError = '';
  booking = false;

  // Availability picker (all times UTC — matches DoctorAvailability's dayOfWeek/time check)
  availability: AvailabilitySlotResponse[] = [];
  loadingAvailability = false;
  availabilityError = '';
  weekStart = mondayOf(new Date());
  activeDate: Date | null = null;
  selectedSlot: string | null = null; // "HH:mm"
  pickedStartIso: string | null = null;
  pickedEndIso: string | null = null;

  // Manual fallback (shown when a doctor has no published working hours, or on request)
  showManualTime = false;
  newStart = '';
  newEnd = '';

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

  /** A DOCTOR (not ADMIN) sees only their own appointments, listed by patient rather than by
   *  doctor — enforced server-side too (AppointmentController: hasRole('DOCTOR') and
   *  #doctorId.toString() == authentication.name). */
  get isDoctorView(): boolean {
    const roles = this.auth.currentUser()?.roles ?? [];
    return roles.includes('ROLE_DOCTOR') && !roles.includes('ROLE_ADMIN');
  }

  ngOnInit(): void {
    const doctorIdParam = this.route.snapshot.queryParamMap.get('doctorId');
    if (doctorIdParam) {
      this.newDoctorId = doctorIdParam;
      this.modalOpen = true;
      this.onDoctorChange();
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
    const source$ = this.isDoctorView
      ? this.appointmentService.getByDoctorId(this.patientId)
      : this.appointmentService.getByPatientId(this.patientId);
    source$.subscribe({
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

  patientLabel(patientId: string): string {
    return `${patientId.slice(0, 8)}…`;
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
      const label = this.isDoctorView ? this.patientLabel(a.patientId) : this.doctorName(a.doctorId);
      const matchesQuery = !query || label.toLowerCase().includes(query);
      return matchesStatus && matchesQuery;
    });
  }

  get sorted(): AppointmentResponse[] {
    const list = [...this.filtered];
    list.sort((a, b) => {
      let cmp = 0;
      switch (this.sortCol) {
        case 'doctor':
          cmp = this.isDoctorView
            ? this.patientLabel(a.patientId).localeCompare(this.patientLabel(b.patientId))
            : this.doctorName(a.doctorId).localeCompare(this.doctorName(b.doctorId));
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
    this.newDoctorId = '';
    this.resetPicker();
    this.modalOpen = true;
  }

  closeModal(): void {
    this.modalOpen = false;
  }

  get selectedDoctor(): DoctorResponse | undefined {
    return this.doctorsById.get(this.newDoctorId);
  }

  get hasAnyAvailability(): boolean {
    return this.availability.length > 0;
  }

  doctorInitials(doctor: DoctorResponse): string {
    return `${doctor.firstName[0] ?? ''}${doctor.lastName[0] ?? ''}`.toUpperCase();
  }

  onDoctorChange(): void {
    this.resetPicker();
    if (!this.newDoctorId) return;

    this.loadingAvailability = true;
    this.doctorService.getAvailability(this.newDoctorId).subscribe({
      next: (res) => {
        this.availability = res;
        this.loadingAvailability = false;
        this.activeDate = this.firstOpenDayInWeek() ?? this.weekDays[0].date;
      },
      error: () => {
        this.availability = [];
        this.availabilityError = "Could not load this doctor's working hours.";
        this.loadingAvailability = false;
        this.showManualTime = true;
      }
    });
  }

  private resetPicker(): void {
    this.availability = [];
    this.availabilityError = '';
    this.weekStart = mondayOf(new Date());
    this.activeDate = null;
    this.selectedSlot = null;
    this.pickedStartIso = null;
    this.pickedEndIso = null;
    this.showManualTime = false;
    this.newStart = '';
    this.newEnd = '';
  }

  /** The 7 dates of the visible week, each carrying that day's generated slot times (UTC). */
  get weekDays(): DayCell[] {
    const today = new Date();
    const days: DayCell[] = [];
    for (let i = 0; i < 7; i++) {
      const date = addUtcDays(this.weekStart, i);
      days.push({
        date,
        dayName: DAY_LABEL[date.getUTCDay()],
        dayNum: date.getUTCDate(),
        isToday: sameUtcDay(date, today),
        slotLabels: this.slotsForDate(date)
      });
    }
    return days;
  }

  private firstOpenDayInWeek(): Date | null {
    const found = this.weekDays.find((d) => d.slotLabels.length > 0);
    return found ? found.date : null;
  }

  /** Every AvailabilitySlot window that applies to this date's day-of-week. */
  private windowsForDate(date: Date): AvailabilitySlotResponse[] {
    const dow = DAY_OF_WEEK[date.getUTCDay()];
    return this.availability.filter((s) => s.dayOfWeek === dow);
  }

  private slotsForDate(date: Date): string[] {
    const labels: string[] = [];
    for (const w of this.windowsForDate(date)) {
      const start = timeToMinutes(w.startTime);
      const end = timeToMinutes(w.endTime);
      for (let t = start; t + SLOT_MINUTES <= end; t += SLOT_MINUTES) {
        labels.push(minutesToLabel(t));
      }
    }
    return labels.sort();
  }

  get activeDayCell(): DayCell | undefined {
    if (!this.activeDate) return undefined;
    return this.weekDays.find((d) => sameUtcDay(d.date, this.activeDate!));
  }

  isActiveDate(date: Date): boolean {
    return !!this.activeDate && sameUtcDay(date, this.activeDate);
  }

  get groupedSlots(): SlotGroup[] {
    const cell = this.activeDayCell;
    if (!cell) return [];
    const groups: Record<string, string[]> = { Morning: [], Afternoon: [], Evening: [] };
    for (const label of cell.slotLabels) {
      const mins = timeToMinutes(label);
      const key = mins < 12 * 60 ? 'Morning' : mins < 17 * 60 ? 'Afternoon' : 'Evening';
      groups[key].push(label);
    }
    return ['Morning', 'Afternoon', 'Evening']
      .map((label) => ({ label, times: groups[label] }))
      .filter((g) => g.times.length > 0);
  }

  weekRangeLabel(): string {
    const end = addUtcDays(this.weekStart, 6);
    const fmt = (d: Date) => `${MONTH_LABEL[d.getUTCMonth()]} ${d.getUTCDate()}`;
    return `${fmt(this.weekStart)} – ${fmt(end)}`;
  }

  prevWeek(): void {
    this.weekStart = addUtcDays(this.weekStart, -7);
    this.selectDay(this.firstOpenDayInWeek() ?? this.weekDays[0].date);
  }

  nextWeek(): void {
    this.weekStart = addUtcDays(this.weekStart, 7);
    this.selectDay(this.firstOpenDayInWeek() ?? this.weekDays[0].date);
  }

  selectDay(date: Date): void {
    this.activeDate = date;
    this.selectedSlot = null;
    this.pickedStartIso = null;
    this.pickedEndIso = null;
  }

  selectSlot(label: string): void {
    if (!this.activeDate) return;
    this.selectedSlot = label;
    const [h, m] = label.split(':').map(Number);
    const start = new Date(this.activeDate);
    start.setUTCHours(h, m, 0, 0);
    const end = new Date(start);
    end.setUTCMinutes(end.getUTCMinutes() + SLOT_MINUTES);
    this.pickedStartIso = start.toISOString();
    this.pickedEndIso = end.toISOString();
    this.bookError = '';
  }

  toggleManualTime(): void {
    this.showManualTime = !this.showManualTime;
    if (this.showManualTime) {
      this.selectedSlot = null;
      this.pickedStartIso = null;
      this.pickedEndIso = null;
    }
  }

  get selectedSlotEndLabel(): string | null {
    if (!this.selectedSlot) return null;
    return minutesToLabel(timeToMinutes(this.selectedSlot) + SLOT_MINUTES);
  }

  get canBook(): boolean {
    if (!this.newDoctorId) return false;
    if (this.showManualTime) return !!this.newStart && !!this.newEnd;
    return !!this.pickedStartIso && !!this.pickedEndIso;
  }

  book(): void {
    if (!this.canBook) return;
    this.bookError = '';
    this.booking = true;

    const scheduledStart = this.showManualTime ? new Date(this.newStart).toISOString() : this.pickedStartIso!;
    const scheduledEnd = this.showManualTime ? new Date(this.newEnd).toISOString() : this.pickedEndIso!;

    this.appointmentService
      .book({
        patientId: this.patientId,
        doctorId: this.newDoctorId,
        scheduledStart,
        scheduledEnd,
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
