package com.healthplatform.doctor.domain;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "departments")
public class Department {

    // Assigned in the constructor, not @GeneratedValue: DoctorService.create() reads getId()
    // right after save() to build the DoctorCreatedEvent, before a generated value would land.
    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String name;

    protected Department() {}

    public Department(String name) {
        this.id = UUID.randomUUID();
        this.name = name;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
}
