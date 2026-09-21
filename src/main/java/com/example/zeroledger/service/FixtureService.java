package com.example.zeroledger.service;

import static com.example.zeroledger.domain.LinearAlgebra.identity;
import static com.example.zeroledger.domain.LinearAlgebra.rotationX;

import com.example.zeroledger.domain.AnalysisConfig;
import com.example.zeroledger.domain.Calibration;
import com.example.zeroledger.domain.Run;
import com.example.zeroledger.domain.Sample;
import com.example.zeroledger.repository.WindTunnelRepository;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class FixtureService {
    private final WindTunnelRepository repository;

    public FixtureService(WindTunnelRepository repository) {
        this.repository = repository;
    }

    public void reset() {
        Run runA = new Run("RUN-20260921-A", "带前后空载的标准 run", 0.50, 0.80, 0.30);
        Run runB = new Run("RUN-20260921-B", "相邻 run，用于证明不会借用空载", 0.50, 0.80, 0.30);

        Calibration calibration2023 = new Calibration(
                "v2023-initial",
                "v2023-初装标定",
                new double[][]{
                        {1.00, 0.00, 0.00, 0.00, 0.00, 0.00},
                        {0.00, 1.00, 0.00, 0.00, 0.00, 0.00},
                        {0.00, 0.00, 1.00, 0.00, 0.00, 0.00},
                        {0.00, 0.00, 0.00, 1.00, 0.00, 0.00},
                        {0.00, 0.00, 0.00, 0.00, 1.00, 0.00},
                        {0.00, 0.00, 0.00, 0.00, 0.00, 1.00}
                },
                identity(3),
                new double[]{0.10, 0.00, 0.00},
                "ALPHA_THEN_BETA_ZY",
                10);
        Calibration calibration2024 = new Calibration(
                "v2024-recheck",
                "v2024-车间复核",
                new double[][]{
                        {1.02, 0.00, 0.00, 0.00, 0.00, 0.00},
                        {0.00, 0.98, 0.00, 0.00, 0.00, 0.00},
                        {0.00, 0.00, 1.01, 0.00, 0.00, 0.00},
                        {0.00, 0.00, 0.00, 1.03, 0.00, 0.00},
                        {0.00, 0.00, 0.00, 0.00, 0.97, 0.00},
                        {0.00, 0.00, 0.00, 0.00, 0.00, 1.00}
                },
                rotationX(0.0),
                new double[]{0.10, 0.02, 0.00},
                "ALPHA_THEN_BETA_ZY",
                20);

        List<Sample> samples = List.of(
                sample("RUN-20260921-A", 0.0, "TARE", new double[]{40, -10, 20, 5, -3, 2}, 0, 0, 0, 18.0,
                        true, "试验前空载"),
                sample("RUN-20260921-A", 10.0, "MEASURED", new double[]{142, 50, -82, 17, -15, 10},
                        0.12, 0.04, 1000, 18.4, true, "正动压测点"),
                sample("RUN-20260921-A", 18.0, "MEASURED", new double[]{144, 51, -80, 18, -14, 11},
                        0.12, 0.04, 0, 18.8, true, "零动压诊断点"),
                sample("RUN-20260921-A", 30.0, "TARE", new double[]{52, -4, 32, 8, 0, 5}, 0, 0, 0, 19.5,
                        true, "试验后空载"),
                sample("RUN-20260921-A", 45.0, "MEASURED", new double[]{148, 54, -76, 20, -11, 13},
                        0.18, -0.03, 950, 20.2, true, "晚于空载包络，必须外推"),
                sample("RUN-20260921-B", 8.0, "MEASURED", new double[]{999, 999, 999, 999, 999, 999},
                        0.1, 0.1, 1000, 20.0, true, "本 run 无空载，不能借用 A"));

        repository.replaceAll(List.of(runA, runB), samples, List.of(calibration2023, calibration2024),
                List.of(new AnalysisConfig("RUN-20260921-A", "LINEAR", "ALPHA_THEN_BETA_ZY",
                                "v2024-recheck"),
                        new AnalysisConfig("RUN-20260921-B", "LINEAR", "ALPHA_THEN_BETA_ZY",
                                "v2024-recheck")));
    }

    private Sample sample(String runId, double time, String kind, double[] raw, double alpha, double beta,
                          double q, double temperature, boolean accepted, String note) {
        return new Sample(null, runId, time, kind, raw, alpha, beta, q, temperature, accepted, note);
    }
}
