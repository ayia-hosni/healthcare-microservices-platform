package com.healthplatform.doctor.domain;

import jakarta.persistence.*;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Doctor aggregate root; id shared with identity-service User id (same cross-context convention as Patient). */
@Entity
@Table(name = "doctors")
public class Doctor {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String firstName;

    @Column(nullable = false)
    private String lastName;

    @Column(nullable = false)
    private String specialty;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    private Department department;

    @OneToMany(mappedBy = "doctor", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<AvailabilitySlot> availability = new HashSet<>();

    @Version
    private Long version;

    protected Doctor() {}

    public Doctor(UUID id, String firstName, String lastName, String specialty, Department department) {
        this.id = id;
        this.firstName = firstName;
        this.lastName = lastName;
        this.specialty = specialty;
        this.department = department;
    }

    public UUID getId() { return id; }
    public String getFirstName() { return firstName; }
    public String getLastName() { return lastName; }
    public String getSpecialty() { return specialty; }
    public Department getDepartment() { return department; }
    public Set<AvailabilitySlot> getAvailability() { return availability; }

    public void addAvailabilitySlot(AvailabilitySlot slot) {
        slot.assignTo(this);
        availability.add(slot);
    }
}
