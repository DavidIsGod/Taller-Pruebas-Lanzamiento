package com.circleguard.notification.integration;

import com.circleguard.notification.service.RoomReservationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * INTEGRATION TEST (Taller 2 - point 3b).
 *
 * Verifies the cross-service contract between PROMOTION-SERVICE (publisher of
 * `circle.fenced` events) and NOTIFICATION-SERVICE (CircleFencedListener).
 *
 * The notification service must, on receiving a fence event, cancel the room
 * reservation for the affected circle.
 */
@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.kafka.consumer.value-deserializer=org.apache.kafka.common.serialization.StringDeserializer"
})
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {"circle.fenced"},
        bootstrapServersProperty = "spring.embedded.kafka.brokers")
@Tag("integration")
class PromotionToNotificationKafkaIT {

    @Autowired private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired private ObjectMapper json;
    @Autowired private EmbeddedKafkaBroker broker;

    @MockBean private RoomReservationService roomReservationService;

    @Test
    @DisplayName("Int-6: 'circle.fenced' event triggers room cancellation in notification-service")
    void circleFencedTriggersRoomCancellation() throws Exception {
        when(roomReservationService.cancelReservation("circle-99", "B201"))
                .thenReturn(CompletableFuture.completedFuture(null));

        String event = json.writeValueAsString(Map.of(
                "circleId",   "circle-99",
                "locationId", "B201",
                "reason",     "CONFIRMED_CASE"
        ));
        kafkaTemplate.send("circle.fenced", "circle-99", event).get(5, TimeUnit.SECONDS);

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
                verify(roomReservationService, timeout(0))
                        .cancelReservation(eq("circle-99"), eq("B201"))
        );
    }
}
