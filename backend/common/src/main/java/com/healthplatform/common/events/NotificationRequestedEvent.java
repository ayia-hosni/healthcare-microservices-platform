package com.healthplatform.common.events;

/** Payload for the event published when any service needs a notification delivered to a user. */
public record NotificationRequestedEvent(
        String recipientId,
        String channel,   // EMAIL | SMS | PUSH
        String templateCode,
        String payloadJson
) {}
