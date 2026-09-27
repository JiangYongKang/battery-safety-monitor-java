package com.github.highcumontoa.batterysafetymonitorjava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * @author Vincent
 * @version 1.0
 */
@SpringBootTest(properties = {"simulator.enabled=false", "server.port=0"})
public class ApplicationTests {
    @Test
    public void contentLoad() {
    }
}
