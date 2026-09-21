package com.windtunnel.zeroledger.engine;

import com.windtunnel.zeroledger.domain.RunSettings;
import com.windtunnel.zeroledger.domain.Sample;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AnalysisEngineTest {

    static Calibration cal() {
        return new Calibration("t", "test", LinAlg.identity(6), LinAlg.identity(3),
                new double[]{0.0, -0.15, 0.20}, 0.12, 0.30, 0.40);
    }

    static Sample sample(long id, String kind, double t, double[] ch,
                         double q, double a, double b) {
        return new Sample(id, 1, t, kind, ch, a, b, q, 281.0, false);
    }

    static double[] v(double... x) {
        return x;
    }

    @Test
    @SuppressWarnings("unchecked")
    void linearDriftIsSubtractedAndBodyForcesRecovered() {
        // 漂移直线: 每通道 d(t)=t/100；两个空载锨点 t=0,100；测点 t=40 -> drift=0.4
        List<Sample> samples = new ArrayList<>();
        samples.add(sample(1, "TARE", 0, v(0, 0, 0, 0, 0, 0), 0, 0, 0));
        samples.add(sample(2, "TARE", 100, v(1, 1, 1, 1, 1, 1), 0, 0, 0));
        samples.add(sample(3, "TEST", 40, v(120.4, -29.6, -799.6, 8.4, 40.4, -9.6),
                1200, 0, 0));

        RunSettings rs = new RunSettings(1, "r", "LINEAR", "YZ", "t");
        Map<String, Object> out = AnalysisEngine.analyzeRun(rs, samples, cal());
        Map<String, Object> row = ((List<Map<String, Object>>) out.get("testRows")).get(0);
        Map<String, Object> chain = (Map<String, Object>) row.get("chain");

        assertEquals(false, row.get("extrapolated"));
        assertVec((List<Double>) ((Map<?, ?>) chain.get("drift")).get("values"),
                v(0.4, 0.4, 0.4, 0.4, 0.4, 0.4), 1e-9);
        assertVec((List<Double>) ((Map<?, ?>) chain.get("fModel")).get("values"),
                v(120, -30, -800), 1e-9);
    }

    @Test
    @SuppressWarnings("unchecked")
    void momentShiftUsesCrossProductTermByTerm() {
        List<Sample> samples = new ArrayList<>();
        samples.add(sample(1, "TARE", 0, v(0, 0, 0, 0, 0, 0), 0, 0, 0));
        samples.add(sample(2, "TARE", 100, v(0, 0, 0, 0, 0, 0), 0, 0, 0));
        // F=(100,0,0)，天平中心力矩 0。r=(0,-.15,.2)
        // r×F = (0, .2*100, .15*100) = (0,20,15)
        samples.add(sample(3, "TEST", 50, v(100, 0, 0, 0, 0, 0), 1000, 0, 0));

        RunSettings rs = new RunSettings(1, "r", "LINEAR", "YZ", "t");
        Map<String, Object> out = AnalysisEngine.analyzeRun(rs, samples, cal());
        Map<String, Object> row = ((List<Map<String, Object>>) out.get("testRows")).get(0);
        Map<String, Object> chain = (Map<String, Object>) row.get("chain");
        assertVec((List<Double>) ((Map<?, ?>) chain.get("rCrossF")).get("values"),
                v(0, 20, 15), 1e-9);
        assertVec((List<Double>) ((Map<?, ?>) chain.get("mRef")).get("values"),
                v(0, 20, 15), 1e-9);

        List<Map<String, Object>> trace = (List<Map<String, Object>>) row.get("momentShiftTrace");
        // My 分量必须包含两项: +rz*Fx = 20, -rx*Fz = 0
        Map<String, Object> myTrace = trace.get(1);
        assertEquals("my", myTrace.get("component"));
        List<Map<String, Object>> terms = (List<Map<String, Object>>) myTrace.get("crossTerms");
        assertEquals("+ rz * Fx", terms.get(0).get("term"));
        assertEquals(20.0, ((Number) terms.get(0).get("value")).doubleValue(), 1e-9);
        assertEquals("- rx * Fz", terms.get(1).get("term"));
        assertEquals(0.0, ((Number) terms.get(1).get("value")).doubleValue(), 1e-9);
        assertEquals(20.0, ((Number) myTrace.get("rCrossF")).doubleValue(), 1e-9);
    }

    @Test
    @SuppressWarnings("unchecked")
    void rotationOrderSwapProducesVisibleDifference() {
        // beta != 0 时 YZ 与 ZY 必须给出不同的风轴力
        List<Sample> samples = new ArrayList<>();
        samples.add(sample(1, "TARE", 0, v(0, 0, 0, 0, 0, 0), 0, 0, 0));
        samples.add(sample(2, "TARE", 100, v(0, 0, 0, 0, 0, 0), 0, 0, 0));
        samples.add(sample(3, "TEST", 40, v(120, -30, -800, 8, 40, -10), 1200, 5, 2));

        Map<String, Object> yz = AnalysisEngine.analyzeRun(
                new RunSettings(1, "r", "LINEAR", "YZ", "t"), samples, cal());
        Map<String, Object> zy = AnalysisEngine.analyzeRun(
                new RunSettings(1, "r", "LINEAR", "ZY", "t"), samples, cal());
        List<Double> fyz = (List<Double>) ((Map<?, ?>) ((Map<?, ?>)
                ((List<Map<String, Object>>) yz.get("testRows")).get(0).get("chain")).get("fWind"))
                .get("values");
        List<Double> fzy = (List<Double>) ((Map<?, ?>) ((Map<?, ?>)
                ((List<Map<String, Object>>) zy.get("testRows")).get(0).get("chain")).get("fWind"))
                .get("values");
        double dist = 0;
        for (int i = 0; i < 3; i++) {
            dist += (fyz.get(i) - fzy.get(i)) * (fyz.get(i) - fzy.get(i));
        }
        assertEquals(2.41921, Math.sqrt(dist), 1e-3,
                "交换旋转次序应在 beta 非零时产生可见差异");
        // 期望值（由独立脚本预算）
        assertVec(fyz, v(190.2381, -25.7938, -786.4122), 1e-3);
    }

    @Test
    @SuppressWarnings("unchecked")
    void nonPositiveDynamicPressureGivesNoInfiniteCoefficientsButKeepsRaw() {
        List<Sample> samples = new ArrayList<>();
        samples.add(sample(1, "TARE", 0, v(0, 0, 0, 0, 0, 0), 0, 0, 0));
        samples.add(sample(2, "TARE", 100, v(0, 0, 0, 0, 0, 0), 0, 0, 0));
        samples.add(sample(3, "TEST", 30, v(0, 0, 0, 0, 0, 0), 0, 0, 0));

        Map<String, Object> out = AnalysisEngine.analyzeRun(
                new RunSettings(1, "r", "LINEAR", "YZ", "t"), samples, cal());
        Map<String, Object> row = ((List<Map<String, Object>>) out.get("testRows")).get(0);
        assertFalse((Boolean) row.get("qPositive"));
        Map<String, Object> coeffs = (Map<String, Object>) row.get("coefficients");
        for (String k : new String[]{"Cd", "Cy", "CL", "Croll", "Cm", "Cn"}) {
            assertNull(coeffs.get(k), k + " 在 q<=0 时必须为 null");
        }
        // 原始力与矩阵链仍保留
        Map<String, Object> chain = (Map<String, Object>) row.get("chain");
        List<Double> raw = (List<Double>) ((Map<?, ?>) chain.get("raw")).get("values");
        assertEquals(6, raw.size());
        List<String> diag = (List<String>) row.get("diagnostics");
        assertTrue(diag.get(0).contains("动压"));
        // 序列化也不得包含 Infinity/NaN
        String json = com.windtunnel.zeroledger.domain.Json.write(out);
        assertFalse(json.contains("Infinity"));
        assertFalse(json.contains("NaN"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void pointOutsideTareEnvelopeIsFlaggedAsExtrapolation() {
        List<Sample> samples = new ArrayList<>();
        samples.add(sample(1, "TARE", 0, v(0, 0, 0, 0, 0, 0), 0, 0, 0));
        samples.add(sample(2, "TARE", 100, v(1, 1, 1, 1, 1, 1), 0, 0, 0));
        samples.add(sample(3, "TEST", 130, v(100, 0, 0, 0, 0, 0), 1100, 0, 0));

        Map<String, Object> out = AnalysisEngine.analyzeRun(
                new RunSettings(1, "r", "LINEAR", "YZ", "t"), samples, cal());
        Map<String, Object> row = ((List<Map<String, Object>>) out.get("testRows")).get(0);
        assertEquals(true, row.get("extrapolated"));
        List<String> diag = (List<String>) row.get("diagnostics");
        assertTrue(diag.get(0).contains("外推"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void segmentModeHoldsEndpointConstantOutsideEnvelope() {
        List<Sample> samples = new ArrayList<>();
        samples.add(sample(1, "TARE", 0, v(0, 0, 0, 0, 0, 0), 0, 0, 0));
        samples.add(sample(2, "TARE", 100, v(2, 2, 2, 2, 2, 2), 0, 0, 0));
        samples.add(sample(3, "TEST", 130, v(102, 2, 2, 2, 2, 2), 1100, 0, 0));

        Map<String, Object> out = AnalysisEngine.analyzeRun(
                new RunSettings(1, "r", "SEGMENT", "YZ", "t"), samples, cal());
        Map<String, Object> row = ((List<Map<String, Object>>) out.get("testRows")).get(0);
        Map<String, Object> chain = (Map<String, Object>) row.get("chain");
        // 分段模式包络外钳到 100s 处的漂移值 2.0，而不是线性延伸的 2.6
        assertVec((List<Double>) ((Map<?, ?>) chain.get("drift")).get("values"),
                v(2, 2, 2, 2, 2, 2), 1e-9);
        assertEquals(true, row.get("extrapolated"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectingATareFallsBackWhenOnlyOneRemains() {
        List<Sample> s1 = new ArrayList<>();
        s1.add(sample(1, "TARE", 0, v(0, 0, 0, 0, 0, 0), 0, 0, 0));
        s1.add(sample(2, "TARE", 100, v(2, 2, 2, 2, 2, 2), 0, 0, 0));
        s1.add(sample(3, "TEST", 50, v(101, 1, 1, 1, 1, 1), 1000, 0, 0));
        Map<String, Object> ok = AnalysisEngine.analyzeRun(
                new RunSettings(1, "r", "LINEAR", "YZ", "t"), s1, cal());
        assertEquals(false, ((List<Map<String, Object>>) ok.get("testRows")).get(0).get("extrapolated"));

        List<Sample> s2 = new ArrayList<>();
        Sample rejected = sample(2, "TARE", 100, v(2, 2, 2, 2, 2, 2), 0, 0, 0);
        s2.add(sample(1, "TARE", 0, v(0, 0, 0, 0, 0, 0), 0, 0, 0));
        s2.add(new Sample(rejected.id(), rejected.runId(), rejected.t(), rejected.kind(),
                rejected.channels(), 0, 0, 0, 281, true));
        s2.add(sample(3, "TEST", 50, v(101, 1, 1, 1, 1, 1), 1000, 0, 0));
        Map<String, Object> bad = AnalysisEngine.analyzeRun(
                new RunSettings(1, "r", "LINEAR", "YZ", "t"), s2, cal());
        Map<String, Object> row = ((List<Map<String, Object>>) bad.get("testRows")).get(0);
        assertEquals(true, row.get("extrapolated"));
        assertEquals(false, row.get("driftUsable"));
    }

    @Test
    void crossProductSignsFollowRightHandRule() {
        // 左右手系易混自检：r=y 方向, F=z 方向 -> r×F 指向 +x
        assertVec(LinAlg.list(LinAlg.cross3(v(0, 1, 0), v(0, 0, 1))),
                v(1, 0, 0), 1e-12);
        // r=x, F=y -> +z
        assertVec(LinAlg.list(LinAlg.cross3(v(1, 0, 0), v(0, 1, 0))),
                v(0, 0, 1), 1e-12);
    }

    static void assertVec(List<Double> actual, double[] expected, double eps) {
        assertEquals(expected.length, actual.size());
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual.get(i), eps,
                    "分量 [" + i + "] 期望 " + expected[i] + " 实际 " + actual.get(i));
        }
    }
}
