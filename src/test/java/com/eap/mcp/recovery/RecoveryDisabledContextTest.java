package com.eap.mcp.recovery;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.ai.mcp.server.enabled=false")
class RecoveryDisabledContextTest {

    @Test
    void defaultDisabledRecoveryShouldNotRequirePostgresOrRabbitMq() {
        // Context startup is the assertion: disabled recovery must not contact its infrastructure.
    }
}
