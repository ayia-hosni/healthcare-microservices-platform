package com.healthplatform.notification.messaging;

import com.healthplatform.common.events.NotificationRequestedEvent;
import com.healthplatform.notification.domain.NotificationLog;
import com.healthplatform.notification.repository.NotificationLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the RabbitMQ job handler that "sends" the notification. The repository is
 * mocked; no broker involved.
 */
class NotificationJobListenerTest {

    private NotificationLogRepository notificationLogRepository;
    private NotificationJobListener listener;

    @BeforeEach
    void setUp() {
        notificationLogRepository = mock(NotificationLogRepository.class);
        when(notificationLogRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        listener = new NotificationJobListener(notificationLogRepository);
    }

    @Test
    void emailChannelLogsSent() {
        var event = new NotificationRequestedEvent("patient-1", "EMAIL", "APPT_CONFIRMED", "{}");

        listener.handle(event);

        var captor = org.mockito.ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationLogRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("SENT");
        assertThat(captor.getValue().getRecipientId()).isEqualTo("patient-1");
        assertThat(captor.getValue().getChannel()).isEqualTo("EMAIL");
    }

    @Test
    void smsChannelLogsSent() {
        var event = new NotificationRequestedEvent("patient-2", "SMS", "LAB_RESULTS_READY", "{}");

        listener.handle(event);

        var captor = org.mockito.ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationLogRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("SENT");
    }

    @Test
    void pushChannelLogsSent() {
        var event = new NotificationRequestedEvent("patient-3", "PUSH", "REMINDER", "{}");

        listener.handle(event);

        var captor = org.mockito.ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationLogRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("SENT");
    }

    @Test
    void unknownChannelLogsFailedAndRethrowsSoMessageIsNackedForDlq() {
        var event = new NotificationRequestedEvent("patient-4", "CARRIER_PIGEON", "REMINDER", "{}");

        assertThatThrownBy(() -> listener.handle(event))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown channel");

        var captor = org.mockito.ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationLogRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("FAILED");
        assertThat(captor.getValue().getRecipientId()).isEqualTo("patient-4");
    }
}
