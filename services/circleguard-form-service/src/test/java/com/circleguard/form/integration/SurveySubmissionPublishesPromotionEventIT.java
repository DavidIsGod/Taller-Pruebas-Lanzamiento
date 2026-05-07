package com.circleguard.form.integration;

import com.circleguard.form.model.HealthSurvey;
import com.circleguard.form.service.HealthSurveyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * INTEGRATION TEST (Taller 2 - point 3b).
 *
 * Validates the cross-service contract between FORM-SERVICE (producer) and
 * PROMOTION-SERVICE (consumer):
 *   POST /api/v1/surveys -> survey persisted -> event published on
 *   `survey.submitted` containing { anonymousId, hasSymptoms, timestamp }.
 *
 * If this contract changes, promotion-service's status engine breaks.
 */
@SpringBootTest
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1,
        topics = {"survey.submitted"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@Tag("integration")
class SurveySubmissionPublishesPromotionEventIT {

    @Autowired private HealthSurveyService surveyService;
    @Autowired private EmbeddedKafkaBroker broker;
    @Autowired private ObjectMapper json;

    @Test
    @DisplayName("Int-5: submitting a survey publishes 'survey.submitted' to Kafka with correct schema")
    void surveySubmissionPublishesPromotionEvent() throws Exception {
        var props = KafkaTestUtils.consumerProps("it-survey", "true", broker);
        props.put("auto.offset.reset", "earliest");
        props.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        props.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        ConsumerFactory<String, String> cf = new DefaultKafkaConsumerFactory<>(props);

        try (var consumer = cf.createConsumer()) {
            consumer.subscribe(java.util.List.of("survey.submitted"));

            UUID anon = UUID.randomUUID();
            HealthSurvey saved = surveyService.submitSurvey(HealthSurvey.builder()
                    .anonymousId(anon)
                    .hasFever(true)
                    .hasCough(false)
                    .exposureDate(LocalDate.now().minusDays(1))
                    .build());

            assertThat(saved.getId()).isNotNull();

            ConsumerRecord<String, String> rec = KafkaTestUtils.getSingleRecord(
                    consumer, "survey.submitted", Duration.ofSeconds(10));
            assertThat(rec).isNotNull();
            assertThat(rec.key()).isEqualTo(anon.toString());

            var node = json.readTree(rec.value());
            assertThat(node.get("anonymousId").asText()).isEqualTo(anon.toString());
            assertThat(node.has("hasSymptoms")).isTrue();
            assertThat(node.has("timestamp")).isTrue();
        }
    }
}
