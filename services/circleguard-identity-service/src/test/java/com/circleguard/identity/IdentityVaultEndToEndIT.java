package com.circleguard.identity;

import com.circleguard.identity.repository.IdentityMappingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * INTEGRATION TEST (Taller 2 - point 3b).
 *
 * Validates the full identity-service stack: HTTP layer -> service ->
 * H2 repository -> Kafka publisher. The Kafka publish step is the contract
 * that other services (notification-service / promotion-service) consume.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1,
        topics = {"audit.identity.accessed"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@Tag("integration")
class IdentityVaultEndToEndIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private IdentityMappingRepository repository;
    @Autowired private EmbeddedKafkaBroker broker;
    @Autowired private ObjectMapper json;

    @Test
    @DisplayName("Int-1: POST /map persists row, returns deterministic anonymousId for same input")
    void mapPersistsAndIsIdempotent() throws Exception {
        long before = repository.count();

        String body = json.writeValueAsString(Map.of("realIdentity", "alice@university.edu"));
        String firstId  = mockMvc.perform(post("/api/v1/identities/map").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String secondId = mockMvc.perform(post("/api/v1/identities/map").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(firstId).isEqualTo(secondId);
        assertThat(repository.count()).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("Int-2: lookup with permission emits an audit event on Kafka (cross-service contract)")
    @WithMockUser(authorities = "identity:lookup")
    void lookupEmitsAuditEvent() throws Exception {
        UUID id = UUID.randomUUID();
        String body = json.writeValueAsString(Map.of("realIdentity", "kafka-bob@university.edu"));
        String mapped = mockMvc.perform(post("/api/v1/identities/map").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        UUID anonymousId = UUID.fromString(json.readTree(mapped).get("anonymousId").asText());

        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps("it-audit", "true", broker);
        consumerProps.put("auto.offset.reset", "earliest");
        consumerProps.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        consumerProps.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        ConsumerFactory<String, String> cf = new DefaultKafkaConsumerFactory<>(consumerProps);

        try (var consumer = cf.createConsumer()) {
            consumer.subscribe(List.of("audit.identity.accessed"));

            mockMvc.perform(get("/api/v1/identities/lookup/{id}", anonymousId))
                    .andExpect(status().isOk());

            ConsumerRecord<String, String> record = KafkaTestUtils.getSingleRecord(consumer, "audit.identity.accessed",
                    Duration.ofSeconds(10));
            assertThat(record).isNotNull();
            assertThat(record.value()).contains(anonymousId.toString());
        }
    }
}
