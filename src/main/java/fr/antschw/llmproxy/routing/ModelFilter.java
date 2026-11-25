package fr.antschw.llmproxy.routing;

import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Include/exclude filter on model ids, with case-insensitive glob patterns where {@code *}
 * matches any sequence of characters (e.g. {@code *embed*}, {@code gpt-4o*}).
 *
 * <p>A model is kept when it matches at least one include pattern (or there is none) and no
 * exclude pattern.
 */
public final class ModelFilter implements Predicate<String> {

    private final List<Pattern> includes;
    private final List<Pattern> excludes;

    /**
     * @param includes patterns a model must match; empty means every model
     * @param excludes patterns of models to drop
     */
    public ModelFilter(List<String> includes, List<String> excludes) {
        this.includes = includes.stream().map(ModelFilter::compile).toList();
        this.excludes = excludes.stream().map(ModelFilter::compile).toList();
    }

    @Override
    public boolean test(String model) {
        String id = model.toLowerCase(Locale.ROOT);
        boolean included = includes.isEmpty() || includes.stream().anyMatch(p -> p.matcher(id).matches());
        return included && excludes.stream().noneMatch(p -> p.matcher(id).matches());
    }

    private static Pattern compile(String glob) {
        StringBuilder regex = new StringBuilder();
        for (String part : glob.toLowerCase(Locale.ROOT).split("\\*", -1)) {
            if (!regex.isEmpty()) {
                regex.append(".*");
            }
            regex.append(Pattern.quote(part));
        }
        return Pattern.compile(regex.toString());
    }
}
