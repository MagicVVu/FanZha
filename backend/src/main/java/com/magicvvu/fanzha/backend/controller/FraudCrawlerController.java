package com.magicvvu.fanzha.backend.controller;

import com.magicvvu.fanzha.backend.service.fraud.FraudNewsEtlService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/crawler")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.admin", name = "enabled", havingValue = "true")
public class FraudCrawlerController {
    private final FraudNewsEtlService fraudNewsEtlService;

    @PostMapping("/run")
    public ResponseEntity<AuthController.ApiResponse<String>> run() {
        Thread t = new Thread(fraudNewsEtlService::runOnce);
        t.setDaemon(true);
        t.start();
        return ResponseEntity.ok(AuthController.ApiResponse.ok("ok", "crawler started"));
    }

}
