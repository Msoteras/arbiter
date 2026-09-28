package ar.edu.utn.frba.arbiter.reports.config;

public final class RequesterContext {

    public record Requester(String name, String role) {}

    private static final ThreadLocal<Requester> CURRENT = new ThreadLocal<>();

    private RequesterContext() {
    }

    public static Requester get() {
        return CURRENT.get();
    }

    public static void set(Requester requester) {
        CURRENT.set(requester);
    }

    public static void clear() {
        CURRENT.remove();
    }
}
