package com.healthplatform.notification.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification_logs")
public class NotificationLog {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private String recipientId;

    @Column(nullable = false)
    private String channel;

    @Column(nullable = false)
    private String templateCode;

    @Column(nullable = false)
    private String status; // SENT | FAILED

    @Column(nullable = false)
    private Instant sentAt = Instant.now();

    protected NotificationLog() {}

    public NotificationLog(String recipientId, String channel, String templateCode, String status) {
        this.recipientId = recipientId;
        this.channel = channel;
        this.templateCode = templateCode;
        this.status = status;
    }

    public UUID getId() { return id; }
    public String getRecipientId() { return recipientId; }
    public String getChannel() { return channel; }
    public String getTemplateCode() { return templateCode; }
    public String getStatus() { return status; }
    public Instant getSentAt() { return sentAt; }
}
