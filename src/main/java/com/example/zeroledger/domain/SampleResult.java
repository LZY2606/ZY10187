package com.example.zeroledger.domain;

import java.util.List;

public record SampleResult(Long sampleId, double elapsedSeconds, String kind, double[] rawChannels,
                           double[] driftEstimate, double[] correctedChannels,
                           double[] balanceLoad, double[] modelLoad, double[] crossProductMoment,
                           double[] translatedLoad, double[] windLoad,
                           double[][] balanceMatrix, double[][] balanceToModelRotation,
                           double[][] translationMatrix, double[][] modelToWindRotation,
                           double alphaRadians, double betaRadians, double dynamicPressure,
                           double temperatureCelsius, Double[] coefficients, boolean extrapolated,
                           List<String> diagnostics, List<CoefficientDetail> coefficientDetails) {
}
