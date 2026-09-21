package com.example.zeroledger.domain;

public record AnalysisConfig(String runId, String driftMode, String rotationOrder, String calibrationId) {
    public boolean piecewise() {
        return "PIECEWISE".equals(driftMode);
    }
}
