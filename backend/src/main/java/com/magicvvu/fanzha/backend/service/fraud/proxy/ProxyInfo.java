package com.magicvvu.fanzha.backend.service.fraud.proxy;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ProxyInfo {
    private String host;
    private int port;
    private String username;
    private String password;
}
