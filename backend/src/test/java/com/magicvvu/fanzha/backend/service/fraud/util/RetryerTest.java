package com.magicvvu.fanzha.backend.service.fraud.util;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

public class RetryerTest {

    @Test
    public void executeRetriesAndSucceeds() throws Exception {
        AtomicInteger count = new AtomicInteger(0);
        String result = Retryer.execute("t", 3, 1L, () -> {
            if (count.incrementAndGet() < 2) {
                throw new IllegalStateException("fail");
            }
            return "ok";
        });
        Assertions.assertEquals("ok", result);
        Assertions.assertEquals(2, count.get());
    }
}
