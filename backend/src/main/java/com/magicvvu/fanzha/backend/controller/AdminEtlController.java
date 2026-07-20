package com.magicvvu.fanzha.backend.controller;

import com.magicvvu.fanzha.backend.service.KnowledgeBaseEtlService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/etl")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.admin", name = "enabled", havingValue = "true")
public class AdminEtlController {

    private final KnowledgeBaseEtlService knowledgeBaseEtlService;

    @PostMapping("/knowledge/run")
    public ResponseEntity<AuthController.ApiResponse<String>> runKnowledgeEtl() {
        Thread worker = new Thread(knowledgeBaseEtlService::runEtlJob, "knowledge-etl-manual");
        worker.setDaemon(true);
        worker.start();
        return ResponseEntity.accepted().body(AuthController.ApiResponse.ok("accepted", "ETL started"));
    }
}
