package fr.antschw.lambda;

public class Main {

    public static void main(String[] args) {
        String name = args.length > 0 ? args[0] : "world";
        System.out.println(new GreetingService().greeting(name));
    }
}
