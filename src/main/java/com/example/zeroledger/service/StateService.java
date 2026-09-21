package com.example.zeroledger.service;

import com.example.zeroledger.domain.AnalysisConfig;
import com.example.zeroledger.domain.AnalysisResult;
import com.example.zeroledger.domain.Calibration;
import com.example.zeroledger.domain.Run;
import com.example.zeroledger.domain.Sample;
import com.example.zeroledger.repository.WindTunnelRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class StateService {
    private final WindTunnelRepository repository;
    private final FixtureService fixtureService;
    private final AnalysisService analysisService;

    public StateService(WindTunnelRepository repository, FixtureService fixtureService,
                        AnalysisService analysisService) {
        this.repository = repository;
        this.fixtureService = fixtureService;
        this.analysisService = analysisService;
    }

    public Map<String, Object> state(String runId) {
        String selectedRunId = runId == null || runId.isBlank()
                ? repository.findRuns().stream().findFirst().map(Run::id).orElseThrow()
                : runId;
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("runs", repository.findRuns());
        state.put("calibrations", repository.findCalibrations());
        state.put("selectedRunId", selectedRunId);
        state.put("analysis", analysisService.analyze(selectedRunId));
        return state;
    }

    public ExportBundle exportAll() {
        List<Run> runs = repository.findRuns();
        List<Sample> samples = runs.stream().flatMap(run -> repository.findSamples(run.id()).stream()).toList();
        List<AnalysisConfig> configs = runs.stream()
                .map(run -> repository.findConfig(run.id()).orElse(null))
                .filter(config -> config != null)
                .toList();
        return ExportBundle.of(runs, samples, repository.findCalibrations(), configs);
    }

    public void importBundle(ExportBundle bundle) {
        if (bundle == null || bundle.runs() == null || bundle.samples() == null
                || bundle.calibrations() == null || bundle.analysisConfigs() == null) {
            throw new IllegalArgumentException("导入文件缺少必需集合");
        }
        repository.replaceAll(bundle.runs(), bundle.samples(), bundle.calibrations(),
                bundle.analysisConfigs());
    }

    public void setTareAccepted(long sampleId, boolean accepted) {
        Sample sample = repository.findSample(sampleId)
                .orElseThrow(() -> new IllegalArgumentException("样本不存在"));
        if (!sample.isTare()) {
            throw new IllegalArgumentException("只能拒绝或接受空载锨点");
        }
        repository.setTareAccepted(sampleId, accepted);
    }

    public AnalysisConfig saveConfig(String runId, ConfigRequest request) {
        if (repository.findRun(runId).isEmpty()) {
            throw new IllegalArgumentException("run 不存在");
        }
        String driftMode = normalize(request.driftMode(), "LINEAR", "LINEAR", "PIECEWISE");
        String rotationOrder = normalize(request.rotationOrder(), "ALPHA_THEN_BETA_ZY",
                "ALPHA_THEN_BETA_ZY", "BETA_THEN_ALPHA_YZ");
        String calibrationId = request.calibrationId();
        Calibration calibration = repository.findCalibration(calibrationId)
                .orElseThrow(() -> new IllegalArgumentException("标定版本不存在"));
        AnalysisConfig config = new AnalysisConfig(runId, driftMode, rotationOrder, calibration.id());
        repository.saveConfig(config);
        return config;
    }

    public void resetFixture() {
        fixtureService.reset();
    }

    private String normalize(String value, String fallback, String first, String second) {
        if (first.equals(value) || second.equals(value)) {
            return value;
        }
        return fallback;
    }

    public record ConfigRequest(String driftMode, String rotationOrder, String calibrationId) {
    }
}
