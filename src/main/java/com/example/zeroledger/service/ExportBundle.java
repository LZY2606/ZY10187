package com.example.zeroledger.service;

import com.example.zeroledger.domain.AnalysisConfig;
import com.example.zeroledger.domain.Calibration;
import com.example.zeroledger.domain.Run;
import com.example.zeroledger.domain.Sample;
import java.util.List;

public record ExportBundle(String format, int version, List<Run> runs, List<Sample> samples,
                           List<Calibration> calibrations, List<AnalysisConfig> analysisConfigs) {
    public static ExportBundle of(List<Run> runs, List<Sample> samples, List<Calibration> calibrations,
                                  List<AnalysisConfig> configs) {
        return new ExportBundle("wind-tunnel-zero-ledger", 1, runs, samples, calibrations, configs);
    }
}
