package com.circleguard.form.service;

import com.circleguard.form.model.HealthSurvey;
import com.circleguard.form.model.Question;
import com.circleguard.form.model.QuestionType;
import com.circleguard.form.model.Questionnaire;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UNIT TESTS (Taller 2 - point 3a).
 *
 * Five new unit tests for the SymptomMapper component.  Each test isolates a
 * single branch of the symptom-detection algorithm without bringing up the
 * Spring context.  These would have caught regressions where the mapper was
 * either too permissive (false positives) or missed valid red flags.
 */
@Tag("unit")
class SymptomMapperUnitTest {

    private final SymptomMapper mapper = new SymptomMapper();

    private Question feverYesNoQuestion(UUID id) {
        return Question.builder()
                .id(id)
                .text("Do you have fever above 38°C?")
                .type(QuestionType.YES_NO)
                .orderIndex(1)
                .build();
    }

    private Question coughYesNoQuestion(UUID id) {
        return Question.builder()
                .id(id)
                .text("Persistent cough?")
                .type(QuestionType.YES_NO)
                .orderIndex(2)
                .build();
    }

    @Test
    @DisplayName("Unit-1: fever 'YES' must be classified as symptomatic")
    void detectsFeverAsSymptom() {
        UUID qid = UUID.randomUUID();
        Questionnaire q = Questionnaire.builder().questions(List.of(feverYesNoQuestion(qid))).build();
        HealthSurvey s = HealthSurvey.builder()
                .responses(Map.of(qid.toString(), "YES"))
                .build();

        assertThat(mapper.hasSymptoms(s, q)).isTrue();
    }

    @Test
    @DisplayName("Unit-2: 'NO' to all health questions is non-symptomatic")
    void allNoMeansHealthy() {
        UUID f = UUID.randomUUID(), c = UUID.randomUUID();
        Questionnaire q = Questionnaire.builder()
                .questions(List.of(feverYesNoQuestion(f), coughYesNoQuestion(c)))
                .build();
        HealthSurvey s = HealthSurvey.builder()
                .responses(Map.of(f.toString(), "NO", c.toString(), "NO"))
                .build();

        assertThat(mapper.hasSymptoms(s, q)).isFalse();
    }

    @Test
    @DisplayName("Unit-3: empty responses map must NOT be classified as symptomatic")
    void emptyResponsesIsHealthy() {
        Questionnaire q = Questionnaire.builder().questions(List.of(feverYesNoQuestion(UUID.randomUUID()))).build();
        HealthSurvey s = HealthSurvey.builder().responses(Map.of()).build();

        assertThat(mapper.hasSymptoms(s, q)).isFalse();
    }

    @Test
    @DisplayName("Unit-4: null responses object must be defensively handled (no NPE)")
    void nullResponsesIsHealthyAndDoesNotThrow() {
        Questionnaire q = Questionnaire.builder().questions(List.of(feverYesNoQuestion(UUID.randomUUID()))).build();
        HealthSurvey s = HealthSurvey.builder().responses(null).build();

        assertThat(mapper.hasSymptoms(s, q)).isFalse();
    }

    @Test
    @DisplayName("Unit-5: multi-choice 'symptoms' question with selection is symptomatic")
    void multiChoiceSymptomsIsSymptomatic() {
        UUID id = UUID.randomUUID();
        Question multi = Question.builder()
                .id(id)
                .text("Select all symptoms you currently have")
                .type(QuestionType.MULTI_CHOICE)
                .orderIndex(1)
                .build();

        Questionnaire q = Questionnaire.builder().questions(List.of(multi)).build();
        HealthSurvey s = HealthSurvey.builder()
                .responses(Map.of(id.toString(), "[\"FEVER\",\"COUGH\"]"))
                .build();

        assertThat(mapper.hasSymptoms(s, q)).isTrue();
    }
}
