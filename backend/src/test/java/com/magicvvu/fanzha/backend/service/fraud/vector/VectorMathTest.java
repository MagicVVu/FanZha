package com.magicvvu.fanzha.backend.service.fraud.vector;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

public class VectorMathTest {

    @Test
    public void l2ComputesDistance() {
        List<Float> a = Arrays.asList(0f, 0f, 0f);
        List<Float> b = Arrays.asList(3f, 4f, 0f);
        double d = VectorMath.l2(a, b);
        Assertions.assertEquals(5.0d, d, 1e-6);
    }
}
