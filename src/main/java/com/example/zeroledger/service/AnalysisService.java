package com.example.zeroledger.service;

import static com.example.zeroledger.domain.LinearAlgebra.cross;
import static com.example.zeroledger.domain.LinearAlgebra.crossMatrix;
import static com.example.zeroledger.domain.LinearAlgebra.identity;
import static com.example.zeroledger.domain.LinearAlgebra.multiply;
import static com.example.zeroledger.domain.LinearAlgebra.rotationY;
import static com.example.zeroledger.domain.LinearAlgebra.rotationZ;

import com.example.zeroledger.domain.AnalysisConfig;
import com.example.zeroledger.domain.AnalysisResult;
import com.example.zeroledger.domain.Calibration;
import com.example.zeroledger.domain.CoefficientDetail;
import com.example.zeroledger.domain.CorrectionExplanation;
import com.example.zeroledger.domain.DriftModel;
import com.example.zeroledger.domain.DriftService;
import com.example.zeroledger.domain.Run;
import com.example.zeroledger.domain.Sample;
import com.example.zeroledger.domain.SampleResult;
import com.example.zeroledger.repository.WindTunnelRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class AnalysisService {
    private static final String[] COEFFICIENT_NAMES = {"CX", "CY", "CZ", "Cl", "Cm", "Cn"};

    private final WindTunnelRepository repository;
    private final DriftService driftService;

    public AnalysisService(WindTunnelRepository repository, DriftService driftService) {
        this.repository = repository;
        this.driftService = driftService;
    }

    public AnalysisResult analyze(String runId) {
        Run run = repository.findRun(runId).orElseThrow(() -> new IllegalArgumentException("run 不存在: " + runId));
        List<Sample> samples = repository.findSamples(runId);
        AnalysisConfig config = repository.findConfig(runId).orElseGet(() ->
                new AnalysisConfig(runId, "LINEAR", "ALPHA_THEN_BETA_ZY", "v2024-recheck"));
        Calibration calibration = repository.findCalibration(config.calibrationId())
                .orElseThrow(() -> new IllegalArgumentException("标定版本不存在: " + config.calibrationId()));

        DriftModel driftModel = driftService.fit(samples.stream()
                .filter(Sample::isTare)
                .map(sample -> new DriftService.DriftPoint(sample.elapsedSeconds(), sample.rawChannels(),
                        sample.tareAccepted()))
                .toList(), config.piecewise());

        List<SampleResult> results = samples.stream()
                .map(sample -> analyzeSample(run, sample, calibration, config, driftModel))
                .toList();
        return new AnalysisResult(config, driftModel, results, explanations());
    }

    private SampleResult analyzeSample(Run run, Sample sample, Calibration calibration,
                                       AnalysisConfig config, DriftModel driftModel) {
        double[] driftEstimate = driftService.estimate(driftModel, sample.elapsedSeconds());
        double[] correctedChannels = subtract(sample.rawChannels(), driftEstimate);
        double[] balanceLoad = multiply(calibration.balanceMatrix(), correctedChannels);

        double[][] balanceToModelRotation = expandThreeByThree(calibration.balanceToModelRotation());
        double[] modelLoad = multiply(balanceToModelRotation, balanceLoad);

        double[] modelForce = slice(modelLoad, 0, 3);
        double[] modelMoment = slice(modelLoad, 3, 3);
        double[] crossProductMoment = cross(calibration.referenceToBalance(), modelForce);
        double[] translatedMoment = add(modelMoment, crossProductMoment);
        double[] translatedLoad = concat(modelForce, translatedMoment);

        double[][] rotation3 = modelToWindRotation(sample.alphaRadians(), sample.betaRadians(),
                config.rotationOrder());
        double[][] modelToWindRotation = expandThreeByThree(rotation3);
        double[] windLoad = multiply(modelToWindRotation, translatedLoad);

        double[][] translationMatrix = new double[][]{
                {1, 0, 0, 0, 0, 0},
                {0, 1, 0, 0, 0, 0},
                {0, 0, 1, 0, 0, 0},
                {0, 0, 0, 1, 0, 0},
                {0, 0, 0, 0, 1, 0},
                {0, 0, 0, 0, 0, 1}
        };
        double[][] crossOperator = crossMatrix(calibration.referenceToBalance());
        for (int row = 0; row < 3; row++) {
            System.arraycopy(crossOperator[row], 0, translationMatrix[row + 3], 0, 3);
        }

        List<String> diagnostics = diagnostics(sample, driftModel);
        Double[] coefficients = coefficients(run, windLoad, sample.dynamicPressure());
        List<CoefficientDetail> details = coefficientDetails(run, sample, calibration,
                balanceToModelRotation, translationMatrix, modelToWindRotation, correctedChannels);

        return new SampleResult(sample.id(), sample.elapsedSeconds(), sample.kind(), sample.rawChannels(),
                driftEstimate, correctedChannels, balanceLoad, modelLoad, crossProductMoment, translatedLoad,
                windLoad, calibration.balanceMatrix(), balanceToModelRotation, translationMatrix,
                modelToWindRotation, sample.alphaRadians(), sample.betaRadians(), sample.dynamicPressure(),
                sample.temperatureCelsius(), coefficients, isExtrapolated(sample, driftModel),
                diagnostics, details);
    }

    private double[][] modelToWindRotation(double alpha, double beta, String order) {
        if ("BETA_THEN_ALPHA_YZ".equals(order)) {
            return multiply(rotationY(alpha), rotationZ(beta));
        }
        return multiply(rotationZ(beta), rotationY(alpha));
    }

    private double[][] expandThreeByThree(double[][] threeByThree) {
        double[][] result = identity(6);
        for (int row = 0; row < 3; row++) {
            System.arraycopy(threeByThree[row], 0, result[row], 0, 3);
            System.arraycopy(threeByThree[row], 0, result[row + 3], 3, 3);
        }
        return result;
    }

    private Double[] coefficients(Run run, double[] windLoad, double dynamicPressure) {
        if (dynamicPressure <= 0) {
            return new Double[]{null, null, null, null, null, null};
        }
        double forceDenominator = dynamicPressure * run.referenceArea();
        return new Double[]{
                windLoad[0] / forceDenominator,
                windLoad[1] / forceDenominator,
                windLoad[2] / forceDenominator,
                windLoad[3] / (dynamicPressure * run.referenceArea() * run.wingSpan()),
                windLoad[4] / (dynamicPressure * run.referenceArea() * run.meanChord()),
                windLoad[5] / (dynamicPressure * run.referenceArea() * run.wingSpan())
        };
    }

    private List<CoefficientDetail> coefficientDetails(Run run, Sample sample, Calibration calibration,
                                                       double[][] balanceToModel,
                                                       double[][] translationMatrix,
                                                       double[][] modelToWind, double[] corrected) {
        double[][] correctedToWindWithoutTranslation = multiply(modelToWind,
                multiply(balanceToModel, calibration.balanceMatrix()));
        double[] modelForce = slice(multiply(balanceToModel,
                multiply(calibration.balanceMatrix(), corrected)), 0, 3);
        double[] crossMoment = cross(calibration.referenceToBalance(), modelForce);
        List<CoefficientDetail> details = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            double denominator = denominator(run, sample.dynamicPressure(), i);
            List<CoefficientDetail.Contribution> contributions = new ArrayList<>();
            for (int channel = 0; channel < 6; channel++) {
                Double coefficientContribution = denominator == 0 ? null
                        : correctedToWindWithoutTranslation[i][channel] * corrected[channel] / denominator;
                contributions.add(new CoefficientDetail.Contribution(
                        "untranslated_channel",
                        "校正通道 " + channel + " 经 G、模型、未平移旋转到 " + COEFFICIENT_NAMES[i],
                        corrected[channel], coefficientContribution, corrected));
            }
            if (i >= 3) {
                for (int axis = 0; axis < 3; axis++) {
                    Double value = denominator == 0 ? null
                            : modelToWind[i][axis + 3] * crossMoment[axis] / denominator;
                    contributions.add(new CoefficientDetail.Contribution(
                            "moment_translation",
                            "r×F 的模型轴 " + axis + " 分量旋转后进入 " + COEFFICIENT_NAMES[i],
                            crossMoment[axis], value, crossMoment));
                }
            }
            details.add(new CoefficientDetail(COEFFICIENT_NAMES[i], i, denominator, contributions));
        }
        return details;
    }

    private double denominator(Run run, double dynamicPressure, int coefficientIndex) {
        if (dynamicPressure <= 0) {
            return 0;
        }
        if (coefficientIndex == 4) {
            return dynamicPressure * run.referenceArea() * run.meanChord();
        }
        if (coefficientIndex >= 3) {
            return dynamicPressure * run.referenceArea() * run.wingSpan();
        }
        return dynamicPressure * run.referenceArea();
    }

    private List<String> diagnostics(Sample sample, DriftModel driftModel) {
        List<String> values = new ArrayList<>();
        if ("TARE".equals(sample.kind())) {
            values.add("TARE_SAMPLE");
        }
        if (driftModel.acceptedTareCount() == 0) {
            values.add("NO_ACCEPTED_TARE");
        }
        if (isExtrapolated(sample, driftModel)) {
            values.add("TARE_EXTRAPOLATED");
        }
        if (sample.dynamicPressure() <= 0 && !"TARE".equals(sample.kind())) {
            values.add("DYNAMIC_PRESSURE_NONPOSITIVE");
        }
        return values;
    }

    private boolean isExtrapolated(Sample sample, DriftModel driftModel) {
        return driftModel.acceptedTareCount() > 0
                && (sample.elapsedSeconds() < driftModel.firstTareTime()
                || sample.elapsedSeconds() > driftModel.lastTareTime());
    }

    private List<CorrectionExplanation> explanations() {
        return List.of(
                new CorrectionExplanation("drift", "空载漂移",
                        "已接受空载锨点按六通道分别拟合；测点原值减去漂移估计得到校正通道。"),
                new CorrectionExplanation("balance", "天平矩阵",
                        "校正通道乘以标定版本中的 6×6 矩阵，得到天平坐标力和力矩。"),
                new CorrectionExplanation("model", "模型坐标",
                        "天平坐标经标定旋转矩阵换算到右手模型坐标。"),
                new CorrectionExplanation("translation", "力矩平移",
                        "M_ref = M_model + r×F_model；r 从模型参考中心指向天平中心。"),
                new CorrectionExplanation("wind", "风轴旋转",
                        "α 抬头为正、β 机头向右翼为正；交换旋转次序会改变非零 α/β 下的结果。"),
                new CorrectionExplanation("coefficient", "系数归一化",
                        "力除以 qS，滚转/偏航除以 qSb，俯仰除以 qSc；q 非正时不生成系数。"));
    }

    private double[] subtract(double[] left, double[] right) {
        double[] result = new double[left.length];
        for (int i = 0; i < left.length; i++) {
            result[i] = left[i] - right[i];
        }
        return result;
    }

    private double[] add(double[] left, double[] right) {
        double[] result = new double[left.length];
        for (int i = 0; i < left.length; i++) {
            result[i] = left[i] + right[i];
        }
        return result;
    }

    private double[] slice(double[] source, int offset, int length) {
        double[] result = new double[length];
        System.arraycopy(source, offset, result, 0, length);
        return result;
    }

    private double[] concat(double[] first, double[] second) {
        double[] result = new double[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

}
