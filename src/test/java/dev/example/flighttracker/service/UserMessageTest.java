package dev.example.flighttracker.service;

import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class UserMessageTest {

    @Test
    void testUserMessageAttributes() {
        UserMessage userMessage = UserMessage.from("test");
        // This is the method that was missing in the older version of langchain4j-core
        // but called by langchain4j-anthropic 1.14.0
        assertDoesNotThrow(() -> userMessage.attributes());
    }
}
