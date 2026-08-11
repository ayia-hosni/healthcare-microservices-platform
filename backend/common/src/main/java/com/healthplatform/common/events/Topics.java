package com.healthplatform.common.events;

/** Central registry of Kafka topic names. Keeping these in one place avoids typo drift across services. */
public final class Topics {

    private Topics() {}

    public static final String PATIENT_EVENTS = "patient.events";
    public static final String DOCTOR_EVENTS = "doctor.events";
    public static final String APPOINTMENT_EVENTS = "appointment.events";
    public static final String EMR_EVENTS = "emr.events";
    public static final String BILLING_EVENTS = "billing.events";
    public static final String NOTIFICATION_REQUESTS = "notification.requests";
    public static final String AUDIT_EVENTS = "audit.events";
}
