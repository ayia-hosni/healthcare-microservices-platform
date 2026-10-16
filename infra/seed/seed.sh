#!/usr/bin/env bash
#
# Populates the platform with fake patients, doctors, and appointments by calling the real
# REST APIs — not raw SQL inserts. That matters for three reasons: passwords go through
# identity-service's real BCrypt hashing, the outbox/Kafka event publishing fires exactly as
# it would for a real user, and the shared-UUID convention (Patient.id == identity-service
# User.id — see patient-service's README) is preserved automatically instead of having to be
# faked by hand.
#
# Doctors and staff accounts require an ADMIN-role token. Tokens are RS256 now, so this script
# can't mint one locally — instead it logs in as the dev bootstrap admin
# (admin@healthplatform.local, seeded on startup by identity-service's DevBootstrapAdmin when
# BOOTSTRAP_ADMIN_ENABLED=true) and uses that real access token. Override ADMIN_EMAIL /
# ADMIN_PASSWORD if your stack seeds a different first admin.
#
# Patient records ARE self-service: PatientController's create endpoint is
# hasAnyRole('ADMIN','PATIENT'), so each fake patient registers for real (POST /auth/register)
# and then creates their own patient-service profile with their own token — no admin token
# needed for that part. The patient's UUID (required as PatientRequest.id, which must match
# the identity-service user id by convention) is read directly out of the JWT's own `sub`
# claim client-side, since JwtService.generateAccessToken sets that claim to
# userId.toString() — no separate lookup call or new endpoint required.
#
# NOT idempotent by design: re-running this against a stack that's already been seeded creates
# a second batch of patients/doctors with different emails/ids, not an upsert. Run it once
# against a freshly started stack. See GETTING_STARTED.md's "Seed Data" section.
# The one exception is the "Staff accounts" section below — createStaffAccount rejects a
# duplicate email with 409, which that loop treats as already-there, so re-running is safe
# specifically for those four fixed-email accounts.
#
# Note on PatientRequest: insurance info and medicalHistorySummary have no field in
# PatientRequest and no PATCH endpoint to set them after the fact (only phoneNumber can be
# updated post-creation) — so this script seeds what the API actually exposes: name, date of
# birth, email, phone. It does not attempt to populate insurance/medical history.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

IDENTITY_URL="${IDENTITY_SERVICE_URL:-http://localhost:8081}"
PATIENT_URL="${PATIENT_SERVICE_URL:-http://localhost:8082}"
DOCTOR_URL="${DOCTOR_SERVICE_URL:-http://localhost:8083}"
APPOINTMENT_URL="${APPOINTMENT_SERVICE_URL:-http://localhost:8084}"

ADMIN_EMAIL="${ADMIN_EMAIL:-admin@healthplatform.local}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-SeedStaff-2026!}"

PATIENT_PASSWORD="SeedPatient-2026!"

c_green="\033[0;32m"; c_yellow="\033[0;33m"; c_red="\033[0;31m"; c_reset="\033[0m"
info() { echo -e "${c_green}==>${c_reset} $*"; }
warn() { echo -e "${c_yellow}==>${c_reset} $*"; }
fail() { echo -e "${c_red}==>${c_reset} $*"; exit 1; }

for cmd in curl jq openssl uuidgen; do
  command -v "$cmd" >/dev/null 2>&1 || fail "$cmd is required but not found on PATH."
done

created_doctors=0
created_patients=0
created_appointments=0
failed=0

# ---------------------------------------------------------------------------
# Health checks — fail fast with a clear message rather than a confusing curl
# error mid-run if the stack isn't up yet.
# ---------------------------------------------------------------------------
wait_for() {
  local name="$1" url="$2"
  for _ in $(seq 1 10); do
    curl -sf -o /dev/null "$url/actuator/health" 2>/dev/null && return 0
    sleep 2
  done
  fail "$name doesn't seem to be up at $url (checked /actuator/health). Start the platform first — see GETTING_STARTED.md."
}

info "Checking that the platform is up..."
wait_for "identity-service" "$IDENTITY_URL"
wait_for "patient-service" "$PATIENT_URL"
wait_for "doctor-service" "$DOCTOR_URL"
wait_for "appointment-service" "$APPOINTMENT_URL"

# ---------------------------------------------------------------------------
# base64url decode — used below to read the `sub` claim out of a patient's own
# access token (no padding, -_ instead of +/).
# ---------------------------------------------------------------------------
b64url_decode() {
  local input="$1"
  local remainder=$(( ${#input} % 4 ))
  if [ "$remainder" -eq 2 ]; then input="${input}=="
  elif [ "$remainder" -eq 3 ]; then input="${input}="
  fi
  echo -n "$input" | tr -- '-_' '+/' | openssl base64 -A -d
}

# ---------------------------------------------------------------------------
# Log in as the dev bootstrap admin for the ADMIN-only calls below.
# ---------------------------------------------------------------------------
login_body=$(jq -n --arg email "$ADMIN_EMAIL" --arg password "$ADMIN_PASSWORD" '{email: $email, password: $password}')
login_code=$(curl -s -o /tmp/seed_admin_login.json -w "%{http_code}" \
  -X POST "$IDENTITY_URL/api/v1/auth/login" -H "Content-Type: application/json" -d "$login_body")
[ "$login_code" = "200" ] || fail "Could not log in as $ADMIN_EMAIL (HTTP $login_code). Is BOOTSTRAP_ADMIN_ENABLED=true? $(cat /tmp/seed_admin_login.json)"
ADMIN_TOKEN=$(jq -r '.accessToken' /tmp/seed_admin_login.json)
rm -f /tmp/seed_admin_login.json
info "Logged in as $ADMIN_EMAIL for doctor and staff-account creation."

# ---------------------------------------------------------------------------
# Staff accounts — real identity-service logins (admin/doctor/nurse/billing-clerk),
# created via the admin-only POST /api/v1/auth/staff-accounts endpoint with the admin
# token obtained above. The admin@ row 409s (it's the bootstrap admin already) and is
# skipped. Unlike the doctors below (Doctor *profiles* in doctor-service, not logins),
# these ARE real accounts you can log into the frontend with, and they're independent
# of each other — the ROLE_DOCTOR login isn't linked to any seeded Doctor profile.
#
# Idempotent, unlike the rest of this script: createStaffAccount rejects a
# duplicate email with 409, which this loop treats as "already there" rather
# than a failure — safe to re-run against an already-seeded stack.
# ---------------------------------------------------------------------------
STAFF_PASSWORD="SeedStaff-2026!"
declare -a STAFF_ACCOUNTS=(
  "admin@healthplatform.local:ROLE_ADMIN"
  "doctor@healthplatform.local:ROLE_DOCTOR"
  "nurse@healthplatform.local:ROLE_NURSE"
  "billing@healthplatform.local:ROLE_BILLING_CLERK"
)
created_staff=0

info "Creating staff accounts..."
for entry in "${STAFF_ACCOUNTS[@]}"; do
  email="${entry%%:*}"
  role="${entry##*:}"

  staff_body=$(jq -n --arg email "$email" --arg password "$STAFF_PASSWORD" --arg role "$role" \
    '{email: $email, password: $password, role: $role}')

  http_code=$(curl -s -o /tmp/seed_staff_response.json -w "%{http_code}" \
    -X POST "$IDENTITY_URL/api/v1/auth/staff-accounts" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -H "Content-Type: application/json" \
    -d "$staff_body")

  if [ "$http_code" = "201" ]; then
    created_staff=$((created_staff + 1))
  elif [ "$http_code" = "409" ]; then
    info "Staff account $email already exists — skipping."
  else
    warn "Failed to create staff account $email (HTTP $http_code): $(cat /tmp/seed_staff_response.json)"
    failed=$((failed + 1))
  fi
done
info "Created $created_staff staff account(s). Password for all of them: $STAFF_PASSWORD"

# ---------------------------------------------------------------------------
# Doctors
# ---------------------------------------------------------------------------
declare -a DOCTOR_FIRST=(Elena Marcus Priya Daniel Fatima Robert Ingrid Samuel)
declare -a DOCTOR_LAST=(Vasquez Chen Anand Okafor Al-Sayed Novak Larsen Whitfield)
declare -a DOCTOR_SPECIALTY=("Family Medicine" Cardiology Pediatrics Orthopedics Dermatology "Internal Medicine" Neurology Psychiatry)
declare -a DOCTOR_DEPT=("Primary Care" Cardiology Pediatrics Orthopedics Dermatology "Internal Medicine" Neurology "Behavioral Health")
declare -a DOCTOR_IDS=()

info "Creating doctors..."
for i in "${!DOCTOR_FIRST[@]}"; do
  id=$(uuidgen)
  body=$(jq -n \
    --arg id "$id" \
    --arg first "${DOCTOR_FIRST[$i]}" \
    --arg last "${DOCTOR_LAST[$i]}" \
    --arg specialty "${DOCTOR_SPECIALTY[$i]}" \
    --arg dept "${DOCTOR_DEPT[$i]}" \
    '{id: $id, firstName: $first, lastName: $last, specialty: $specialty, departmentName: $dept}')

  http_code=$(curl -s -o /tmp/seed_doctor_response.json -w "%{http_code}" \
    -X POST "$DOCTOR_URL/api/v1/doctors" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -H "Content-Type: application/json" \
    -d "$body")

  if [ "$http_code" = "201" ]; then
    DOCTOR_IDS+=("$id")
    created_doctors=$((created_doctors + 1))
  else
    warn "Failed to create doctor ${DOCTOR_FIRST[$i]} ${DOCTOR_LAST[$i]} (HTTP $http_code): $(cat /tmp/seed_doctor_response.json)"
    failed=$((failed + 1))
  fi
done
info "Created $created_doctors doctor(s)."

# ---------------------------------------------------------------------------
# Patients — register (real identity-service account) then create the
# patient-service profile using the id decoded from the token's own sub claim.
# ---------------------------------------------------------------------------
declare -a PATIENT_FIRST=(Aya Liam Noor Ethan Sofia Kwame Yuki Isabella Omar Grace \
                          Diego Amara Felix Leilani Hassan Ruth Mateo Chidi Ines Oliver)
declare -a PATIENT_LAST=(Hosni Bennett Rahman Coleman Rossi Mensah Tanaka Ferreira Haddad Whitfield \
                         Alvarez Okonkwo Bergström Kealoha Farouk Goldstein Silva Adeyemi Duarte Fitzgerald)
declare -a PATIENT_DOB=(1990-05-01 1985-11-12 1978-02-20 1995-07-08 1988-09-30 1992-03-15 1975-12-01 1983-06-18 1998-01-25 1970-10-05 \
                        1993-08-14 1987-04-22 1980-02-29 1996-11-03 1991-06-27 1976-09-09 1989-01-19 1994-05-11 1982-07-07 1999-03-03)
declare -a PATIENT_PHONE=(555-0101 555-0102 555-0103 555-0104 555-0105 555-0106 555-0107 555-0108 555-0109 555-0110 \
                          555-0111 555-0112 555-0113 555-0114 555-0115 555-0116 555-0117 555-0118 555-0119 555-0120)
declare -a PATIENT_IDS=()
declare -a PATIENT_TOKENS=()

info "Registering and creating patients..."
for i in "${!PATIENT_FIRST[@]}"; do
  first="${PATIENT_FIRST[$i]}"
  last="${PATIENT_LAST[$i]}"
  email="seed.$(echo "$first$last" | tr '[:upper:]' '[:lower:]').${i}@example.com"

  register_body=$(jq -n --arg email "$email" --arg password "$PATIENT_PASSWORD" '{email: $email, password: $password}')
  http_code=$(curl -s -o /tmp/seed_register_response.json -w "%{http_code}" \
    -X POST "$IDENTITY_URL/api/v1/auth/register" \
    -H "Content-Type: application/json" \
    -d "$register_body")

  if [ "$http_code" != "201" ]; then
    warn "Failed to register patient $first $last <$email> (HTTP $http_code): $(cat /tmp/seed_register_response.json)"
    failed=$((failed + 1))
    continue
  fi

  access_token=$(jq -r '.accessToken' /tmp/seed_register_response.json)
  payload_segment=$(echo "$access_token" | cut -d. -f2)
  patient_id=$(b64url_decode "$payload_segment" | jq -r '.sub')

  patient_body=$(jq -n \
    --arg id "$patient_id" \
    --arg first "$first" \
    --arg last "$last" \
    --arg dob "${PATIENT_DOB[$i]}" \
    --arg email "$email" \
    --arg phone "${PATIENT_PHONE[$i]}" \
    '{id: $id, firstName: $first, lastName: $last, dateOfBirth: $dob, email: $email, phoneNumber: $phone}')

  http_code=$(curl -s -o /tmp/seed_patient_response.json -w "%{http_code}" \
    -X POST "$PATIENT_URL/api/v1/patients" \
    -H "Authorization: Bearer $access_token" \
    -H "Content-Type: application/json" \
    -d "$patient_body")

  if [ "$http_code" = "201" ]; then
    PATIENT_IDS+=("$patient_id")
    PATIENT_TOKENS+=("$access_token")
    created_patients=$((created_patients + 1))
  else
    warn "Failed to create patient profile for $first $last (HTTP $http_code): $(cat /tmp/seed_patient_response.json)"
    failed=$((failed + 1))
  fi
done
info "Created $created_patients patient(s)."

# ---------------------------------------------------------------------------
# Appointments — pair each seeded patient with a doctor (round-robin), one
# appointment each, spread a few days into the future.
# ---------------------------------------------------------------------------
info "Booking appointments..."
if [ "${#DOCTOR_IDS[@]}" -eq 0 ] || [ "${#PATIENT_IDS[@]}" -eq 0 ]; then
  warn "No doctors or no patients were created successfully — skipping appointment booking."
else
  for i in "${!PATIENT_IDS[@]}"; do
    doctor_id="${DOCTOR_IDS[$(( i % ${#DOCTOR_IDS[@]} ))]}"
    patient_id="${PATIENT_IDS[$i]}"
    patient_token="${PATIENT_TOKENS[$i]}"

    # macOS `date` (BSD) syntax — matches this repo's existing macOS-only assumption
    # (see infra/native/run.sh's header comment).
    start=$(date -u -v+$((i + 2))d -v9H -v0M -v0S +%Y-%m-%dT%H:%M:%SZ)
    end=$(date -u -v+$((i + 2))d -v9H -v30M -v0S +%Y-%m-%dT%H:%M:%SZ)

    appt_body=$(jq -n \
      --arg patientId "$patient_id" \
      --arg doctorId "$doctor_id" \
      --arg start "$start" \
      --arg end "$end" \
      --arg key "$(uuidgen)" \
      '{patientId: $patientId, doctorId: $doctorId, scheduledStart: $start, scheduledEnd: $end, idempotencyKey: $key}')

    http_code=$(curl -s -o /tmp/seed_appointment_response.json -w "%{http_code}" \
      -X POST "$APPOINTMENT_URL/api/v1/appointments" \
      -H "Authorization: Bearer $patient_token" \
      -H "Content-Type: application/json" \
      -d "$appt_body")

    if [ "$http_code" = "201" ]; then
      created_appointments=$((created_appointments + 1))
    else
      warn "Failed to book appointment for patient $patient_id (HTTP $http_code): $(cat /tmp/seed_appointment_response.json)"
      failed=$((failed + 1))
    fi
  done
fi
info "Booked $created_appointments appointment(s)."

rm -f /tmp/seed_staff_response.json /tmp/seed_doctor_response.json /tmp/seed_register_response.json /tmp/seed_patient_response.json /tmp/seed_appointment_response.json

echo
info "Done: $created_staff staff accounts, $created_doctors doctors, $created_patients patients, $created_appointments appointments created."
if [ "$failed" -gt 0 ]; then
  warn "$failed record(s) failed — see warnings above."
  exit 1
fi
