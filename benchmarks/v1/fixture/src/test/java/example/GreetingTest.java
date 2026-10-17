package example;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GreetingTest {
    @Test
    void returnsTheDefaultGreeting() {
        assertEquals("Hello", Greeting.message());
    }
}
