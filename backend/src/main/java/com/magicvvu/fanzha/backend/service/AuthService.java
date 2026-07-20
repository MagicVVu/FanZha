package com.magicvvu.fanzha.backend.service;

import com.magicvvu.fanzha.backend.entity.User;

public interface AuthService {
    User register(String accountRaw, String password, String confirmPassword);

    User login(String accountRaw, String password);
}
