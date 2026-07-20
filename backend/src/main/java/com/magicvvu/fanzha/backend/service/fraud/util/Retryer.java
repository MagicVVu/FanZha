package com.magicvvu.fanzha.backend.service.fraud.util;

import java.util.concurrent.Callable;

public class Retryer {
    private Retryer() {
    }

    public static <T> T execute(String name, int maxRetries, long baseBackoffMs, Callable<T> callable) throws Exception {
        long backoff = baseBackoffMs;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                return callable.call();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (Exception e) {
                if (attempt == maxRetries) {
                    throw e;
                }
                Thread.sleep(backoff);
                backoff = Math.min(backoff * 2L, 30_000L);
            }
        }
        throw new IllegalStateException("Retryer unreachable for " + name);
    }
}
