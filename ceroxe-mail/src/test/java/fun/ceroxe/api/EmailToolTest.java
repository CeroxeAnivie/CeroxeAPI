package fun.ceroxe.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class EmailToolTest {

    @Test
    void emailConfigCarriesSmtpAndProxySettings() {
        EmailTool.EmailConfig config = new EmailTool.EmailConfig(
                "smtp.example.com",
                587,
                "user@example.com",
                "secret",
                null,
                null
        );

        assertEquals("smtp.example.com", config.host());
        assertEquals(587, config.port());
        assertEquals("user@example.com", config.username());
        assertEquals("secret", config.password());
        assertNull(config.proxyHost());
        assertNull(config.proxyPort());
    }
}
