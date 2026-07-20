package com.magicvvu.fanzha.backend.controller;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.magicvvu.fanzha.backend.entity.User;
import com.magicvvu.fanzha.backend.service.AuthService;
import lombok.AllArgsConstructor;
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
@RequestMapping("/auth")
@RequiredArgsConstructor
@Validated
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<UserInfo>> register(@Valid @RequestBody RegisterRequest request) {
        try {
            User user = authService.register(request.getAccount(), request.getPassword(), request.getConfirmPassword());
            return ResponseEntity.ok(ApiResponse.ok("注册成功", UserInfo.from(user)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.fail(e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.fail(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.fail("注册失败"));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<UserInfo>> login(@Valid @RequestBody LoginRequest request) {
        try {
            User user = authService.login(request.getAccount(), request.getPassword());
            return ResponseEntity.ok(ApiResponse.ok("登录成功", UserInfo.from(user)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.fail(e.getMessage()));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.fail(e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.fail(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.fail("登录失败"));
        }
    }

    @Data
    public static class RegisterRequest {
        // 兼容前端可能发送的字段名：email / phone / account
        @JsonAlias({"email", "phone", "account"})
        @NotBlank(message = "请填写所有字段")
        private String account;

        @NotBlank(message = "请填写所有字段")
        private String password;

        @JsonAlias({"confirm_password", "confirmPassword"})
        private String confirmPassword;
    }

    @Data
    public static class LoginRequest {
        @JsonAlias({"email", "phone", "account"})
        @NotBlank(message = "请填写所有字段")
        private String account;

        @NotBlank(message = "请填写所有字段")
        private String password;
    }

    @Data
    @AllArgsConstructor
    public static class ApiResponse<T> {
        private boolean success;
        private String message;
        private T data;

        public static <T> ApiResponse<T> ok(String message, T data) {
            return new ApiResponse<>(true, message, data);
        }

        public static <T> ApiResponse<T> fail(String message) {
            return new ApiResponse<>(false, message, null);
        }
    }

    @Data
    @AllArgsConstructor
    public static class UserInfo {
        private Long id;
        private String account;
        private String email;
        private String phone;

        public static UserInfo from(User user) {
            return new UserInfo(user.getId(), user.getAccount(), user.getEmail(), user.getPhone());
        }
    }
}
