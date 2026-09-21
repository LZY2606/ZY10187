package com.example.zeroledger.domain;

public record Calibration(String id, String label, double[][] balanceMatrix,
                          double[][] balanceToModelRotation, double[] referenceToBalance,
                          String rotationOrder, int sortOrder) {
}
