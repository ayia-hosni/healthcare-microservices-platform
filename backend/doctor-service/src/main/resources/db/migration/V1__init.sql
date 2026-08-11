CREATE TABLE departments (
    id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name    VARCHAR(150) NOT NULL UNIQUE
);

CREATE TABLE doctors (
    id              UUID PRIMARY KEY,
    first_name      VARCHAR(100) NOT NULL,
    last_name       VARCHAR(100) NOT NULL,
    specialty       VARCHAR(150) NOT NULL,
    department_id   UUID REFERENCES departments(id),
    version         BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE availability_slots (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    doctor_id   UUID NOT NULL REFERENCES doctors(id) ON DELETE CASCADE,
    day_of_week VARCHAR(10) NOT NULL,
    start_time  TIME NOT NULL,
    end_time    TIME NOT NULL
);

CREATE INDEX idx_doctors_specialty ON doctors (lower(specialty));
