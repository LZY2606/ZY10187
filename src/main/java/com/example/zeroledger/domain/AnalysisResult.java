package com.example.zeroledger.domain;

import java.util.List;

public record AnalysisResult(AnalysisConfig config, DriftModel driftModel, List<SampleResult> samples,
                             List<CorrectionExplanation> explanations) {
}
