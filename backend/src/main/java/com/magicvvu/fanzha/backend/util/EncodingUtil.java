package com.magicvvu.fanzha.backend.util;

public class EncodingUtil {
    public static byte[] normalize(byte[] raw) {
        if (raw == null || raw.length == 0) return new byte[0];
        if (raw.length >= 3 && (raw[0] & 0xFF) == 0xEF && (raw[1] & 0xFF) == 0xBB && (raw[2] & 0xFF) == 0xBF) {
            byte[] out = new byte[raw.length - 3];
            System.arraycopy(raw, 3, out, 0, out.length);
            return out;
        }
        if (raw.length >= 2 && (raw[0] & 0xFF) == 0xFF && (raw[1] & 0xFF) == 0xFE) {
            String s = new String(raw, java.nio.charset.StandardCharsets.UTF_16LE);
            return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        if (raw.length >= 2 && (raw[0] & 0xFF) == 0xFE && (raw[1] & 0xFF) == 0xFF) {
            String s = new String(raw, java.nio.charset.StandardCharsets.UTF_16BE);
            return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        return raw;
    }
}
