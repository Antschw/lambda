package fr.antschw.llmproxy.routing;

import fr.antschw.llmproxy.config.ProxyConfig.Upstream;

/**
 * A real model on a real upstream: the unit the proxy sends a request to.
 *
 * @param upstream the provider
 * @param model    model name as the provider knows it
 */
public record Target(Upstream upstream, String model) {

    /** @return a stable identifier such as {@code provider-a/qwen-coder} */
    public String id() {
        return upstream.name() + "/" + model;
    }

    @Override
    public String toString() {
        return id();
    }
}
