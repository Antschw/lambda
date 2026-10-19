package fr.antschw.lambda;

import jakarta.annotation.Nonnull;
import org.jetbrains.annotations.NotNull;

public class GreetingService {

    @NotNull
    public String greeting(@Nonnull String name) {
        return "hello " + name;
    }
}
