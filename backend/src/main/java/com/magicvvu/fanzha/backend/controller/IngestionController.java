package com.magicvvu.fanzha.backend.controller;

import com.magicvvu.fanzha.backend.service.IngestionService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.NotBlank;
import java.util.List;

@RestController
@RequestMapping("/upload")
@RequiredArgsConstructor
@Validated
@ConditionalOnProperty(prefix = "app.ingestion", name = "enabled", havingValue = "true")
public class IngestionController {
    private final IngestionService ingestionService;

    @PostMapping
    public ResponseEntity<AuthController.ApiResponse<List<FileResult>>> upload(
            @RequestParam("files") @NotNull List<MultipartFile> files
    ) {
        try {
            if (files.isEmpty()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(AuthController.ApiResponse.fail("请至少选择一个文件"));
            }
            if (files.size() > 200) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(AuthController.ApiResponse.fail("批量上传最多 200 个文件"));
            }
            List<FileResult> results = ingestionService.process(files);
            return ResponseEntity.ok(AuthController.ApiResponse.ok("上传成功", results));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(AuthController.ApiResponse.fail(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(AuthController.ApiResponse.fail("服务端处理失败"));
        }
    }

    @PostMapping("/text")
    public ResponseEntity<AuthController.ApiResponse<FileResult>> uploadText(
            @RequestBody TextRequest request
    ) {
        try {
            String content = request.getContent();
            if (content == null || content.trim().isEmpty()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(AuthController.ApiResponse.fail("请填写内容"));
            }
            byte[] raw = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] normalized = com.magicvvu.fanzha.backend.util.EncodingUtil.normalize(raw);
            String masked = com.magicvvu.fanzha.backend.util.SensitiveMasker.mask(new String(normalized, java.nio.charset.StandardCharsets.UTF_8));
            List<com.magicvvu.fanzha.backend.util.ingest.MessageRecord> records =
                    ingestionService.parseSingle("input.txt", "text/plain", masked.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String fingerprint = com.magicvvu.fanzha.backend.util.FingerprintUtil.sha256Hex(normalized);
            FileResult result = new FileResult("input.txt", normalized.length, fingerprint, records.size());
            return ResponseEntity.ok(AuthController.ApiResponse.ok("分析成功", result));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(AuthController.ApiResponse.fail("服务端处理失败"));
        }
    }

    @Data
    @AllArgsConstructor
    public static class FileResult {
        private String filename;
        private long size;
        private String fingerprint;
        private int records;
    }

    @Data
    public static class TextRequest {
        @NotBlank(message = "请填写内容")
        private String content;
    }
}
