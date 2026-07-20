package com.magicvvu.fanzha.backend.controller;

import com.magicvvu.fanzha.backend.util.DeepSeekClient;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;

@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
@Validated
public class AiController {
    private final DeepSeekClient deepSeekClient;

    @PostMapping("/chat")
    public ResponseEntity<AuthController.ApiResponse<String>> chat(@Valid @RequestBody ChatRequest request) {
        try {
            String content = deepSeekClient.chat(request.getSystem(), request.getPrompt(), request.getModel());
            return ResponseEntity.ok(AuthController.ApiResponse.ok("ok", content));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(AuthController.ApiResponse.fail(e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(AuthController.ApiResponse.fail(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(AuthController.ApiResponse.fail("DeepSeek 调用失败"));
        }
    }

    @Data
    public static class ChatRequest {
        private String system;

        @NotBlank(message = "请填写所有字段")
        private String prompt;

        private String model;
    }
}
