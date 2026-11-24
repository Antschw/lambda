package fr.antschw.llmproxy.http;

import com.sun.net.httpserver.Headers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Checks the key a client sends to the proxy.
 *
 * <p>Accepts {@code Authorization: Bearer <key>} (OpenAI style, used by Cline, opencode, pi...)
 * and {@code x-api-key: <key>} (used by some other tools). With no key configured, every
 * request is accepted.
 */
final class Auth {

    private final List<byte[]> keys;

    Auth(Set<String> keys) {
        this.keys = keys.stream().map(k -> k.getBytes(StandardCharsets.UTF_8)).toList();
    }

    boolean enabled() {
        return !keys.isEmpty();
    }

    boolean isAuthorized(Headers headers) {
        if (keys.isEmpty()) {
            return true;
        }
        String authorization = headers.getFirst("Authorization");
        if (authorization != null && authorization.toLowerCase(Locale.ROOT).startsWith("bearer ")
                && matches(authorization.substring(7).trim())) {
            return true;
        }
        String apiKey = headers.getFirst("x-api-key");
        return apiKey != null && matches(apiKey.trim());
    }

    private boolean matches(String candidate) {
        byte[] bytes = candidate.getBytes(StandardCharsets.UTF_8);
        boolean match = false;
        for (byte[] key : keys) {
            // Constant-time comparison, and no short-circuit across keys.
            match |= MessageDigest.isEqual(key, bytes);
        }
        return match;
    }
}
