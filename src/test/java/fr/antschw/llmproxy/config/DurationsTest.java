package fr.antschw.llmproxy.config;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class DurationsTest {

    @ParameterizedTest
    @CsvSource({
            "PT30S, 30000",
            "pt1m, 60000",
            "30, 30000",
            "1.5, 1500",
            "250ms, 250",
            "30s, 30000",
            "5m, 300000",
            "1h, 3600000",
            "6m0s, 360000",
            "1m30.5s, 90500",
            "' 2s ', 2000"
    })
    void parsesSupportedForms(String text, long expectedMillis) {
        assertThat(Durations.parse(text).toMillis()).isEqualTo(expectedMillis);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "soon", "10 minutes", "PTX", "-5s"})
    void rejectsInvalidValues(String text) {
        assertThatIllegalArgumentException().isThrownBy(() -> Durations.parse(text));
    }
}
