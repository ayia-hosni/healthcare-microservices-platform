-- availability_slots.doctor_id is the primary query pattern (a doctor's schedule) and a
-- cascade-delete FK; department_id backs the department roster lookup. Both were unindexed.
CREATE INDEX idx_availability_slots_doctor ON availability_slots (doctor_id);
CREATE INDEX idx_doctors_department ON doctors (department_id);
