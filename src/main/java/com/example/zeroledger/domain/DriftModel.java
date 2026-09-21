package com.example.zeroledger.domain;

import java.util.List;

public record DriftModel(double[] slope, double[] intercept, Double firstTareTime, Double lastTareTime,
                        int acceptedTareCount, boolean piecewise, List<DriftKnot> knots) {
    public record DriftKnot(double time, double[] values) {
    }
}
