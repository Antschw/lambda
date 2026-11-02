package exp.b;

public final class B {
    /**
     * Marqueur de provenance. C'est une constante de compilation : javac la recopie
     * dans A.class, ce qui permet de savoir après coup contre quel b le module a a été compilé.
     * Le script de test la réécrit avant chaque build de b.
     */
    public static final String ORIGIN = "B_ORIGIN=source-initiale";

    private B() {
    }
}
