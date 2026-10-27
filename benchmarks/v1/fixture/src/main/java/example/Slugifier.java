package example;

import java.util.Locale;

public final class Slugifier {
    private Slugifier() {}

    public static String slugify(String input) {
        return input.trim().toLowerCase(Locale.ROOT).replace(' ', '-');
    }
}
