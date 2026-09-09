package dev.realtime;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class RealtimeApplicationTests {

    @Test
    void contextLoads() {
        // M0's entire job: prove the Spring context assembles cleanly with no wiring errors.
        // Deliberately empty — this test earns its keep the day someone adds a bean
        // that fails to start, long before that reaches docker-compose up.
    }
}
