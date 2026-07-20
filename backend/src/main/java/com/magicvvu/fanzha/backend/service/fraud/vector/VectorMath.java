package com.magicvvu.fanzha.backend.service.fraud.vector;

import java.util.List;

public class VectorMath {
    private VectorMath() {
    }

    public static double l2(List<Float> a, List<Float> b) {
        if (a == null || b == null || a.size() != b.size()) {
            throw new IllegalArgumentException("vector size mismatch");
        }
        double sum = 0.0d;
        for (int i = 0; i < a.size(); i++) {
            double d = a.get(i) - b.get(i);
            sum += d * d;
        }
        return Math.sqrt(sum);
    }
}
