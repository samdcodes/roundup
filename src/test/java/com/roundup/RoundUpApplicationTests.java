package com.roundup;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        properties = {"starling.access-token=test-token", "starling.user-agent=round-up-tests"})
class RoundUpApplicationTests {

    @Test
    void contextLoads() {}
}
