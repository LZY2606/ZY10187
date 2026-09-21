package com.example.zeroledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.example.zeroledger.domain.AnalysisConfig;
import com.example.zeroledger.domain.AnalysisResult;
import com.example.zeroledger.domain.CoefficientDetail;
import com.example.zeroledger.domain.SampleResult;
import com.example.zeroledger.repository.WindTunnelRepository;
import com.example.zeroledger.service.AnalysisService;
import com.example.zeroledger.service.FixtureService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AnalysisServiceTest {
    @Autowired
    FixtureService fixtureService;
    @Autowired
    AnalysisService analysisService;
    @Autowired
    WindTunnelRepository repository;

    @BeforeEach
    void setUp() {
        fixtureService.reset();
    }

    @Test
    void zeroDynamicPressureKeepsForcesButEmitsNoInfiniteCoefficients() {
        AnalysisResult result = analysisService.analyze("RUN-20260921-A");
        SampleResult zeroQ = result.samples().get(2);
        assertThat(zeroQ.dynamicPressure()).isZero();
        assertThat(zeroQ.coefficients()).containsOnlyNulls();
        assertThat(zeroQ.diagnostics()).contains("DYNAMIC_PRESSURE_NONPOSITIVE");
        assertThat(zeroQ.rawChannels()).doesNotContain(999);
        assertThat(zeroQ.windLoad()).doesNotContain(Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, Double.NaN);
    }

    @Test
    void latePointMarksExtrapolationWithoutBorrowingAnotherRunTare() {
        AnalysisResult result = analysisService.analyze("RUN-20260921-A");
        SampleResult late = result.samples().get(4);
        assertThat(late.elapsedSeconds()).isEqualTo(45);
        assertThat(late.extrapolated()).isTrue();
        assertThat(late.diagnostics()).contains("TARE_EXTRAPOLATED");

        AnalysisResult runWithoutTare = analysisService.analyze("RUN-20260921-B");
        assertThat(runWithoutTare.samples()).singleElement()
                .satisfies(sample -> assertThat(sample.diagnostics()).contains("NO_ACCEPTED_TARE"));
        assertThat(runWithoutTare.samples().get(0).driftEstimate()).containsExactly(0, 0, 0, 0, 0, 0);
    }

    @Test
    void momentTranslationContributionsCanBeTracedAndSumToCoefficient() {
        AnalysisResult result = analysisService.analyze("RUN-20260921-A");
        SampleResult sample = result.samples().get(1);
        assertThat(sample.crossProductMoment()[0]).isCloseTo(-2.1412, within(1e-12));
        assertThat(sample.crossProductMoment()[1]).isCloseTo(10.706, within(1e-12));
        assertThat(sample.crossProductMoment()[2]).isCloseTo(3.6848, within(1e-12));
        CoefficientDetail pitch = sample.coefficientDetails().get(4);
        double sum = pitch.contributions().stream()
                .map(CoefficientDetail.Contribution::coefficientContribution)
                .mapToDouble(Double::doubleValue)
                .sum();
        assertThat(sum).isCloseTo(sample.coefficients()[4], within(1e-12));
        assertThat(pitch.contributions()).anySatisfy(contribution -> {
            assertThat(contribution.stage()).isEqualTo("moment_translation");
            assertThat(contribution.formula()).contains("r×F");
        });
    }

    @Test
    void rotationOrderChangeCreatesVisibleDifference() {
        AnalysisResult alphaFirst = analysisService.analyze("RUN-20260921-A");
        fixtureService.reset();
        var config = new AnalysisConfig("RUN-20260921-A", "LINEAR", "BETA_THEN_ALPHA_YZ", "v2024-recheck");
        repository.saveConfig(config);
        AnalysisResult betaFirst = analysisService.analyze("RUN-20260921-A");
        assertThat(betaFirst.samples().get(1).coefficients()[1])
                .isNotEqualTo(alphaFirst.samples().get(1).coefficients()[1]);
    }

}
