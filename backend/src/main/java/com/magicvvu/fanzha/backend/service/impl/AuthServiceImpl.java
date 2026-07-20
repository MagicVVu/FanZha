package com.magicvvu.fanzha.backend.service.impl;

import com.magicvvu.fanzha.backend.dao.UserRepository;
import com.magicvvu.fanzha.backend.entity.User;
import com.magicvvu.fanzha.backend.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$");
    private static final Pattern PHONE_PATTERN = Pattern.compile("^1\\d{10}$");

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public User register(String accountRaw, String password, String confirmPassword) {
        NormalizedAccount account = normalizeAccount(accountRaw);

        if (password == null || password.trim().isEmpty()) {
            throw new IllegalArgumentException("请填写所有字段");
        }
        if (confirmPassword != null && !confirmPassword.equals(password)) {
            throw new IllegalArgumentException("两次输入的密码不一致");
        }

        if (userRepository.existsByAccount(account.getAccount())) {
            throw new IllegalStateException("账号已存在");
        }
        if (account.getEmail() != null && userRepository.existsByEmail(account.getEmail())) {
            throw new IllegalStateException("账号已存在");
        }
        if (account.getPhone() != null && userRepository.existsByPhone(account.getPhone())) {
            throw new IllegalStateException("账号已存在");
        }

        User user = new User();
        user.setAccount(account.getAccount());
        user.setEmail(account.getEmail());
        user.setPhone(account.getPhone());
        user.setPasswordHash(hashPassword(password));
        user.setCreateTime(LocalDateTime.now());
        return userRepository.save(user);
    }

    @Override
    public User login(String accountRaw, String password) {
        NormalizedAccount account = normalizeAccount(accountRaw);

        User user = userRepository.findByAccount(account.getAccount()).orElseThrow(() -> new IllegalStateException("账号不存在"));
        if (!matchesPassword(password, user.getPasswordHash())) {
            throw new SecurityException("密码错误");
        }
        if (!user.getPasswordHash().startsWith("$2")) {
            user.setPasswordHash(passwordEncoder.encode(password));
            userRepository.save(user);
        }
        return user;
    }

    private String hashPassword(String password) {
        return passwordEncoder.encode(password);
    }

    private boolean matchesPassword(String rawPassword, String stored) {
        if (rawPassword == null || stored == null) {
            return false;
        }
        if (stored.startsWith("$2")) {
            return passwordEncoder.matches(rawPassword, stored);
        }

        // Backward compatibility for local databases created before BCrypt migration.
        String[] parts = stored.split(":", 2);
        if (parts.length != 2) {
            return false;
        }
        byte[] salt = Base64.getDecoder().decode(parts[0]);
        byte[] expected = Base64.getDecoder().decode(parts[1]);
        byte[] actual = sha256(salt, rawPassword.getBytes(StandardCharsets.UTF_8));
        return constantTimeEquals(expected, actual);
    }

    private byte[] sha256(byte[] salt, byte[] passwordBytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt);
            digest.update(passwordBytes);
            return digest.digest();
        } catch (Exception e) {
            throw new IllegalStateException("密码处理失败");
        }
    }

    private boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length; i++) {
            result |= a[i] ^ b[i];
        }
        return result == 0;
    }

    private NormalizedAccount normalizeAccount(String raw) {
        // 兼容界面“邮箱 / 手机号”单输入框：含 @ 则按邮箱处理，否则按 11 位手机号处理
        if (raw == null || raw.trim().isEmpty()) {
            throw new IllegalArgumentException("请填写所有字段");
        }

        String trimmed = raw.trim();
        if (trimmed.contains("@")) {
            String normalizedEmail = trimmed.toLowerCase(Locale.ROOT);
            if (!EMAIL_PATTERN.matcher(normalizedEmail).matches()) {
                throw new IllegalArgumentException("请输入有效邮箱或手机号");
            }
            return new NormalizedAccount(normalizedEmail, normalizedEmail, null);
        }

        String digits = trimmed.replaceAll("\\s+", "");
        if (!PHONE_PATTERN.matcher(digits).matches()) {
            throw new IllegalArgumentException("请输入有效邮箱或手机号");
        }
        return new NormalizedAccount(digits, null, digits);
    }

    private static class NormalizedAccount {
        private final String account;
        private final String email;
        private final String phone;

        private NormalizedAccount(String account, String email, String phone) {
            this.account = account;
            this.email = email;
            this.phone = phone;
        }

        public String getAccount() {
            return account;
        }

        public String getEmail() {
            return email;
        }

        public String getPhone() {
            return phone;
        }
    }
}
