package com.example.zeroledger.domain;

import java.util.List;

public record CoefficientDetail(String coefficient, Integer outputIndex, double denominator,
                                List<Contribution> contributions) {
    public record Contribution(String stage, String formula, double inputValue, Double coefficientContribution,
                               double[] stageVector) {
    }
}
