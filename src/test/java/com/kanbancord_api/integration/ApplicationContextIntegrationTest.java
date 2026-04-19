package com.kanbancord_api.integration;

import com.kanbancord_api.integration.config.TestcontainersConfiguration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Tag("integration")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ApplicationContextIntegrationTest {

    @Test
    void contextLoads() {
    }
}
