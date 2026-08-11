-- Cascade-delete FKs on the encounter child tables, queried whenever an encounter is loaded
-- with its diagnoses/medications/lab results — none had an index.
CREATE INDEX idx_diagnoses_encounter ON diagnoses (encounter_id);
CREATE INDEX idx_medications_encounter ON medications (encounter_id);
CREATE INDEX idx_lab_results_encounter ON lab_results (encounter_id);
