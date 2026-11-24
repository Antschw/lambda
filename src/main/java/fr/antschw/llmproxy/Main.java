package fr.antschw.llmproxy;

import fr.antschw.llmproxy.config.ConfigException;
import fr.antschw.llmproxy.config.ConfigLoader;
import fr.antschw.llmproxy.config.ProxyConfig;
import fr.antschw.llmproxy.http.ProxyServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.CountDownLatch;

/**
 * Entry point.
 *
 * <p>The configuration file is, in order: the first argument, the {@code LLM_PROXY_CONFIG}
 * environment variable, or {@code config.json} in the working directory.
 */
public final class Main {

    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    private Main() {
    }

    /**
     * @param args optional path to the configuration file
     */
    public static void main(String[] args) {
        Path configFile = Path.of(args.length > 0 ? args[0]
                : System.getenv().getOrDefault("LLM_PROXY_CONFIG", "config.json"));
        if (!Files.isRegularFile(configFile)) {
            LOG.error("Configuration file not found: {}", configFile.toAbsolutePath());
            System.exit(2);
        }

        ProxyConfig config;
        try {
            config = new ConfigLoader().load(configFile);
        } catch (ConfigException | IOException e) {
            LOG.error("Invalid configuration {}: {}", configFile.toAbsolutePath(), e.getMessage());
            System.exit(2);
            return;
        }

        ProxyServer server = new ProxyServer(config, Clock.systemUTC());
        try {
            server.start();
        } catch (IOException e) {
            LOG.error("Cannot listen on {}:{}: {}", config.listen().host(), config.listen().port(), e.getMessage());
            System.exit(1);
        }

        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(() -> {
            LOG.info("Shutting down, waiting up to {}s for in-flight requests",
                    config.timeouts().shutdown().toSeconds());
            server.close();
            stopped.countDown();
        }));

        LOG.info("LLM proxy listening on http://{}:{}/v1 ({} upstream(s), client authentication {})",
                config.listen().host(), server.port(), config.upstreams().size(),
                config.localApiKeys().isEmpty() ? "disabled" : "enabled");
        server.pool().status().forEach((model, targets) -> LOG.info("Model '{}' -> {}", model,
                targets.stream().map(t -> t.target()).toList()));

        try {
            stopped.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
