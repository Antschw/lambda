package fr.antschw.llmproxy.routing;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ModelFilterTest {

    private static final List<String> MODELS = List.of(
            "gpt-4o", "gpt-4o-mini", "text-embedding-3-small", "Qwen/Qwen3-Coder", "whisper-1");

    @Test
    void keepsEverythingWithoutPatterns() {
        assertThat(MODELS.stream().filter(new ModelFilter(List.of(), List.of()))).containsExactlyElementsOf(MODELS);
    }

    @Test
    void appliesCaseInsensitiveIncludeAndExcludeGlobs() {
        ModelFilter filter = new ModelFilter(List.of("gpt-*", "qwen/*"), List.of("*mini"));

        assertThat(MODELS.stream().filter(filter)).containsExactly("gpt-4o", "Qwen/Qwen3-Coder");
    }

    @Test
    void treatsOtherCharactersLiterally() {
        ModelFilter filter = new ModelFilter(List.of(), List.of("*embed*", "whisper-1"));

        assertThat(filter.test("whisper-1")).isFalse();
        assertThat(filter.test("whisper-10")).isTrue();
        assertThat(filter.test("text-embedding-3-small")).isFalse();
        assertThat(new ModelFilter(List.of("a.b"), List.of()).test("axb")).isFalse();
    }
}
