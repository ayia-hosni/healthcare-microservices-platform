-- patient_id, invoice_id are the FK/join columns actually queried (patient statements, cascade
-- deletes on invoice removal) but had no index — full scans on invoices/payments/insurance_claims.
CREATE INDEX idx_invoices_patient ON invoices (patient_id);
CREATE INDEX idx_payments_invoice ON payments (invoice_id);
CREATE INDEX idx_insurance_claims_invoice ON insurance_claims (invoice_id);
