package fr.antschw.llmproxy.config;

/** Thrown when the configuration is invalid. The message is meant for the user. */
public class ConfigException extends RuntimeException {

    /**
     * @param message description of the problem
     */
    public ConfigException(String message) {
        super(message);
    }
}
