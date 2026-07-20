package com.magicvvu.fanzha.backend.service.fraud.captcha;

public interface CaptchaSolver {
    String solve(byte[] imageBytes) throws Exception;
}
