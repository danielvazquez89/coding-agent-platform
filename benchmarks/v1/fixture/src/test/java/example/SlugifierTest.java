package example;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SlugifierTest {
    @Test
    void turnsWordsIntoASlug() {
        assertEquals("hello-world", Slugifier.slugify("Hello World"));
    }
}
