package com.example.zeroledger.domain;

public record Sample(Long id, String runId, double elapsedSeconds, String kind,
                     double[] rawChannels, double alphaRadians, double betaRadians,
                     double dynamicPressure, double temperatureCelsius, boolean tareAccepted,
                     String note) {
    public boolean isTare() {
        return "TARE".equals(kind);
    }
}
