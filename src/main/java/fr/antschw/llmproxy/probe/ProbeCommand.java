package fr.antschw.llmproxy.probe;

import fr.antschw.llmproxy.config.ConfigException;
import fr.antschw.llmproxy.config.ConfigLoader;
import fr.antschw.llmproxy.config.ProxyConfig;
import fr.antschw.llmproxy.config.ProxyConfig.Upstream;

import java.io.IOException;
import java.io.PrintStream;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Command line entry of the context probe.
 *
 * <pre>
 * java -jar llm-proxy.jar probe &lt;upstream&gt; &lt;model&gt; [--config config.json] [--min 4000] [--max 131072]
 * </pre>
 */
public final class ProbeCommand {

    private ProbeCommand() {
    }

    /**
     * @param args command arguments, without the leading {@code probe}
     * @param out  where to print
     * @return process exit code
     */
    public static int run(String[] args, PrintStream out) {
        List<String> positional = new ArrayList<>();
        String configFile = System.getenv().getOrDefault("LLM_PROXY_CONFIG", "config.json");
        long min = 4_000;
        long max = 131_072;
        try {
            for (int i = 0; i < args.length; i++) {
                // "max=65536" is accepted as well as "--max 65536": PowerShell mangles arguments starting with "-".
                if (args[i].matches("(config|min|max)=.*")) {
                    String[] pair = args[i].split("=", 2);
                    switch (pair[0]) {
                        case "config" -> configFile = pair[1];
                        case "min" -> min = Long.parseLong(pair[1]);
                        default -> max = Long.parseLong(pair[1]);
                    }
                    continue;
                }
                switch (args[i]) {
                    case "--config" -> configFile = args[++i];
                    case "--min" -> min = Long.parseLong(args[++i]);
                    case "--max" -> max = Long.parseLong(args[++i]);
                    case "-h", "--help" -> {
                        usage(out);
                        return 0;
                    }
                    default -> positional.add(args[i]);
                }
            }
        } catch (ArrayIndexOutOfBoundsException | NumberFormatException e) {
            usage(out);
            return 2;
        }
        if (positional.size() != 2 || min < 100 || max < min) {
            usage(out);
            return 2;
        }
        String upstreamName = positional.get(0);
        String model = positional.get(1);

        ProxyConfig config;
        try {
            config = new ConfigLoader().load(Path.of(configFile));
        } catch (ConfigException | IOException e) {
            out.println("Invalid configuration " + configFile + ": " + e.getMessage());
            return 2;
        }
        Optional<Upstream> upstream = config.upstreams().stream().filter(u -> u.name().equals(upstreamName)).findFirst();
        if (upstream.isEmpty()) {
            out.println("Unknown upstream '" + upstreamName + "'. Known: "
                    + config.upstreams().stream().map(Upstream::name).toList());
            return 2;
        }

        out.printf(Locale.ROOT, "Probing %s / %s from ~%,d to ~%,d tokens (each step is a real request)%n",
                upstreamName, model, min, max);
        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(config.timeouts().connect())
                .proxy(ProxySelector.getDefault())
                .build()) {
            ContextProbe probe = new ContextProbe(upstream.get(), model, client, config.context().charsPerToken(),
                    config.timeouts().request(), out::println);
            ContextProbe.Result result = probe.run(min, max);
            report(result, upstreamName, max, out);
            return 0;
        } catch (IOException e) {
            out.println("Probe failed: " + e.getMessage());
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 1;
        }
    }

    private static void report(ContextProbe.Result result, String upstream, long max, PrintStream out) {
        out.println();
        if (result.largestWorking() == 0) {
            out.println("Even the smallest size failed: the model may not follow the test, try a larger model or --min lower.");
            return;
        }
        if (result.smallestFailed() == 0) {
            out.printf(Locale.ROOT, "Every size up to ~%,d tokens worked: no limit found below --max.%n", max);
            return;
        }
        out.printf(Locale.ROOT, "Works up to ~%,d tokens, fails from ~%,d tokens.%n",
                result.largestWorking(), result.smallestFailed());
        boolean silent = result.steps().stream().anyMatch(s -> s.outcome() == ContextProbe.Outcome.FORGOT);
        if (silent) {
            out.println("The upstream does not report an error: it silently drops the start of long conversations.");
        }
        out.printf(Locale.ROOT, "Suggested setting for upstream '%s': \"maxContextTokens\": %d%n",
                upstream, result.suggestedLimit());
        out.println("(sizes are the proxy's estimates, the unit maxContextTokens is compared against;"
                + " use \"modelContextTokens\" if only some models are affected)");
    }

    private static void usage(PrintStream out) {
        out.println("Usage: java -jar llm-proxy.jar probe <upstream> <model> [--config config.json] [--min 4000] [--max 131072]");
        out.println("       (min=4000 max=131072 config=... also accepted)");
        out.println("Finds the context size an upstream really handles (each step is a real request).");
    }
}
