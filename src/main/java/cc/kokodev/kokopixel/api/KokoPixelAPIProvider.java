package cc.kokodev.kokopixel.api;

public class KokoPixelAPIProvider {
    private static KokoPixelAPI instance;

    public static void set(KokoPixelAPI api) { instance = api; }

    public static KokoPixelAPI get() {
        if (instance == null) throw new IllegalStateException("KokoPixelAPI not initialized");
        return instance;
    }
}
