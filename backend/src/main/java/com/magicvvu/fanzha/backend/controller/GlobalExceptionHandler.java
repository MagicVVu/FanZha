package com.magicvvu.fanzha.backend.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<AuthController.ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String message = "请填写所有字段";
        FieldError fieldError = e.getBindingResult().getFieldError();
        if (fieldError != null) {
            String defaultMessage = fieldError.getDefaultMessage();
            if (defaultMessage != null && !defaultMessage.trim().isEmpty()) {
                message = defaultMessage;
            }
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(AuthController.ApiResponse.fail(message));
    }
}
