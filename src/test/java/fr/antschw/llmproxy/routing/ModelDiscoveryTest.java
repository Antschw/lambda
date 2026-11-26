package fr.antschw.llmproxy.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class ModelDiscoveryTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void readsTheOpenAiFormatKeepingDuplicatesForTheCaller() throws IOException {
        String body = "{\"object\":\"list\",\"data\":[{\"id\":\"m1\"},{\"id\":\"m2\"},{\"id\":\"m1\"},{\"id\":\"\"}]}";

        assertThat(ModelDiscovery.parseIds(JSON.readTree(body))).containsExactly("m1", "m2", "m1");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"models\":[{\"name\":\"m1\"},{\"name\":\"m2\"}]}",
            "[\"m1\",\"m2\"]",
            "{\"data\":[\"m1\",{\"id\":\"m2\"}]}"
    })
    void acceptsLenientFormats(String body) throws IOException {
        assertThat(ModelDiscovery.parseIds(JSON.readTree(body))).containsExactly("m1", "m2");
    }

    @Test
    void returnsNullWithoutModelArray() throws IOException {
        assertThat(ModelDiscovery.parseIds(JSON.readTree("{\"error\":\"nope\"}"))).isNull();
    }

    @Test
    void readsAdvertisedContextSizesKeepingTheLargestForDuplicates() throws IOException {
        String body = "{\"data\":[{\"id\":\"a\",\"context_length\":32768},{\"id\":\"b\",\"max_model_len\":8192},"
                + "{\"id\":\"a\",\"context_length\":131072},{\"id\":\"c\"},{\"id\":\"d\",\"context_window\":0}]}";

        assertThat(ModelDiscovery.parseLimits(JSON.readTree(body)))
                .containsExactly(java.util.Map.entry("a", 131_072), java.util.Map.entry("b", 8_192));
    }
}
