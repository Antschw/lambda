package fr.antschw.lambda;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class GreetingService {

    public String greet(String name) {
        String who = (name == null || name.isBlank()) ? "world" : name;
        return "Hello " + who + " !";
    }
}
