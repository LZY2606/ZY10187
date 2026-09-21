package com.windtunnel.zeroledger.engine;

import com.windtunnel.zeroledger.domain.RunSettings;
import com.windtunnel.zeroledger.domain.Sample;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.windtunnel.zeroledger.engine.LinAlg.cross3;
import static com.windtunnel.zeroledger.engine.LinAlg.matMul;
import static com.windtunnel.zeroledger.engine.LinAlg.matVec;
import static com.windtunnel.zeroledger.engine.LinAlg.rotY;
import static com.windtunnel.zeroledger.engine.LinAlg.rotZ;

/**
 * 零线帐分析引擎：纯函数，不访问数据库。
 *
 * 计算链（每个测点保留全部中间向量/矩阵）：
 *   raw[6]  六通道原值
 *   d[6]    空载漂移估计（本 run、选定模式）
 *   z[6]=raw-d 零线修正后通道
 *   w[6]=K z 天平坐标力/力矩（天平中心）
 *   力:  wF -> mount -> fModel
 *   力矩平移: wM -> mount -> mBalance(model axes); mRef = mBalance + rRef x fModel
 *   旋转: R_w = Ry(-alpha) Rz(beta)（YZ 次序，默认）或 Rz(beta) Ry(-alpha)（ZY）
 *   fWind = R_w fModel, mWind = R_w mRef
 *   系数: 仅在 q>0 时生成，否则为 null（绝不产生 Infinity/NaN 系数）
 */
public final class AnalysisEngine {

    public static final String ORDER_YZ = "YZ";
    public static final String ORDER_ZY = "ZY";
    private static final int OUT = 6;

    private AnalysisEngine() {
    }

    public static Map<String, Object> analyzeRun(RunSettings settings,
                                                 List<Sample> runSamples,
                                                 Calibration cal) {
        DriftModel.Mode mode = "SEGMENT".equals(settings.driftMode())
                ? DriftModel.Mode.SEGMENT : DriftModel.Mode.LINEAR;

        List<Sample> tares = new ArrayList<>();
        List<Sample> tests = new ArrayList<>();
        for (Sample s : runSamples) {
            if ("TARE".equals(s.kind()) && !s.tareExcluded()) {
                tares.add(s);
            } else if ("TEST".equals(s.kind())) {
                tests.add(s);
            }
        }
        DriftModel.FitResult fit = DriftModel.fit(tares, mode);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", settings.runId());
        out.put("name", settings.name());
        out.put("driftMode", mode.name());
        out.put("rotationOrder", settings.rotationOrder());
        out.put("calibVersion", settings.calibVersion());
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("valid", fit.valid());
        if (fit.valid()) {
            envelope.put("tMin", round0(fit.tMin()));
            envelope.put("tMax", round0(fit.tMax()));
        } else {
            envelope.put("tMin", null);
            envelope.put("tMax", null);
        }
        out.put("tareEnvelope", envelope);
        out.put("usedTares", fit.usedTares().stream().map(AnalysisEngine::sampleRef).toList());
        out.put("rejectedTares", runSamples.stream()
                .filter(s -> "TARE".equals(s.kind()) && s.tareExcluded())
                .map(AnalysisEngine::sampleRef).toList());
        out.put("calibration", calInfo(cal));

        List<Map<String, Object>> tareRows = new ArrayList<>();
        for (Sample s : runSamples.stream().filter(s -> "TARE".equals(s.kind())).toList()) {
            tareRows.add(tareRow(s, fit));
        }
        out.put("tareRows", tareRows);

        List<Map<String, Object>> testRows = new ArrayList<>();
        for (Sample s : tests.stream().sorted(java.util.Comparator.comparingDouble(Sample::t)).toList()) {
            testRows.add(testRow(s, fit, settings, cal));
        }
        out.put("testRows", testRows);
        return out;
    }

    private static Map<String, Object> sampleRef(Sample s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sampleId", s.id());
        m.put("t", round0(s.t()));
        m.put("channels", LinAlg.list(LinAlg.roundVec(s.channels(), 6)));
        return m;
    }

    private static Map<String, Object> calInfo(Calibration cal) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", cal.code());
        m.put("label", cal.label());
        m.put("K", LinAlg.list(LinAlg.roundMat(cal.k(), 6)));
        m.put("mount", LinAlg.list(LinAlg.roundMat(cal.mount(), 6)));
        m.put("rRef", LinAlg.list(LinAlg.roundVec(cal.rRef(), 6)));
        m.put("areaM2", cal.areaM2());
        m.put("spanM", cal.spanM());
        m.put("chordM", cal.chordM());
        return m;
    }

    private static Map<String, Object> tareRow(Sample s, DriftModel.FitResult fit) {
        Map<String, Object> m = sampleRef(s);
        m.put("excluded", s.tareExcluded());
        if (!s.tareExcluded() && fit.valid()) {
            double[] at = fit.valueAt(s.t());
            m.put("fitAtT", LinAlg.list(LinAlg.roundVec(at, 6)));
            m.put("residual", LinAlg.list(LinAlg.roundVec(
                    LinAlg.sub(s.channels(), at), 6)));
        }
        return m;
    }

    private static Map<String, Object> testRow(Sample s, DriftModel.FitResult fit,
                                               RunSettings settings, Calibration cal) {
        double[] raw = s.channels();
        double[] drift = fit.valid() ? fit.valueAt(s.t()) : new double[6];
        boolean extrapolated = !fit.valid() || !fit.insideEnvelope(s.t());
        boolean driftUsable = fit.valid();

        // 零线：漂移不足时零线修正不执行，z 保留原值并给出诊断
        double[] zeroed = driftUsable ? LinAlg.sub(raw, drift) : raw.clone();

        // 标定解耦：通道 -> 天平坐标六分量
        double[] w = matVec(cal.k(), zeroed);
        double[] wF = new double[]{w[0], w[1], w[2]};
        double[] wM = new double[]{w[3], w[4], w[5]};

        // 安装矩阵：天平坐标 -> 模型坐标
        double[] fModel = matVec(cal.mount(), wF);
        double[] mBalance = matVec(cal.mount(), wM);

        // 力矩平移（必须叉积）：mRef = mBalance + rRef x fModel
        double[] tau = cross3(cal.rRef(), fModel);
        double[] mRef = LinAlg.add(mBalance, tau);

        // 模型 -> 风轴。alpha/beta 角度为度，正方向见 README。
        double a = Math.toRadians(s.alphaDeg());
        double b = Math.toRadians(s.betaDeg());
        double[][] rYa = rotY(-a);
        double[][] rZb = rotZ(b);
        String order = ORDER_ZY.equals(settings.rotationOrder()) ? ORDER_ZY : ORDER_YZ;
        double[][] rWind = order.equals(ORDER_YZ) ? matMul(rYa, rZb) : matMul(rZb, rYa);
        double[] fWind = matVec(rWind, fModel);
        double[] mWind = matVec(rWind, mRef);

        // 系数：q<=0 不生成，字段为 null
        double q = s.qPa();
        boolean qOk = q > 0;
        Double cd  = qOk ? -fWind[0] / (q * cal.areaM2()) : null;
        Double cy  = qOk ?  fWind[1] / (q * cal.areaM2()) : null;
        Double cl  = qOk ? -fWind[2] / (q * cal.areaM2()) : null;
        Double cll = qOk ?  mWind[0] / (q * cal.areaM2() * cal.spanM()) : null;
        Double cm  = qOk ?  mWind[1] / (q * cal.areaM2() * cal.chordM()) : null;
        Double cn  = qOk ?  mWind[2] / (q * cal.areaM2() * cal.spanM()) : null;

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("sampleId", s.id());
        row.put("t", round0(s.t()));
        row.put("kind", "TEST");
        row.put("qPa", round0(q));
        row.put("alphaDeg", round0(s.alphaDeg()));
        row.put("betaDeg", round0(s.betaDeg()));
        row.put("tempK", round0(s.tempK()));
        row.put("extrapolated", extrapolated);
        row.put("driftUsable", driftUsable);
        row.put("qPositive", qOk);

        Map<String, Object> chain = new LinkedHashMap<>();
        chain.put("raw", vec(raw));
        chain.put("drift", driftUsable ? vec(drift) : nanVec(drift));
        chain.put("zeroed", vec(zeroed));
        chain.put("K", mat(cal.k()));
        chain.put("wBalance6", vec(w));
        chain.put("wForce", vec(wF));
        chain.put("wMomentAtBalance", vec(wM));
        chain.put("mount", mat(cal.mount()));
        chain.put("fModel", vec(fModel));
        chain.put("mBalanceModelAxes", vec(mBalance));
        chain.put("rRef", vec(cal.rRef()));
        chain.put("rCrossF", vec(tau));
        chain.put("mRef", vec(mRef));
        chain.put("alphaRad", round0(a));
        chain.put("betaRad", round0(b));
        chain.put("RyNegAlpha", mat(rYa));
        chain.put("RzBeta", mat(rZb));
        chain.put("rotationOrder", order);
        chain.put("RWind", mat(rWind));
        chain.put("fWind", vec(fWind));
        chain.put("mWind", vec(mWind));
        row.put("chain", chain);

        // 力矩平移逐项贡献（可点击回查）：r x f 的每个分量拆成两个力分量乘积
        row.put("momentShiftTrace", momentShiftTrace(cal.rRef(), fModel, mBalance, mRef));

        Map<String, Object> coeffs = new LinkedHashMap<>();
        coeffs.put("Cd", cd); coeffs.put("Cy", cy); coeffs.put("CL", cl);
        coeffs.put("Croll", cll); coeffs.put("Cm", cm); coeffs.put("Cn", cn);
        row.put("coefficients", coeffs);

        // 每个系数的构成回查（含分母、参与的原始力/力矩）
        row.put("coefficientTrace", coefficientTrace(qOk, q, cal, fWind, mWind));

        List<String> diag = new ArrayList<>();
        if (extrapolated) {
            diag.add("试验时间 t=" + fmt(s.t()) + "s 超出本 run 空载锨点包络 ["
                    + fmt(fit.tMin()) + ", " + fmt(fit.tMax()) + "]s，结果标记为外推；"
                    + (fit.mode() == DriftModel.Mode.LINEAR
                        ? "线性漂移按拟合直线延伸。" : "分段漂移钳到最近锨点（不借用其他 run）。"));
        }
        if (!driftUsable) {
            diag.add("可用空载锨点不足 2 个，未执行零线修正，六通道保留原值。");
        }
        if (!qOk) {
            diag.add("动压 q=" + fmt(q) + " Pa 非正，按约定不生成气动力系数（对应字段留空），原始力与诊断完整保留。");
        }
        row.put("diagnostics", diag);
        return row;
    }

    private static List<Map<String, Object>> momentShiftTrace(double[] r, double[] f,
                                                              double[] mBal, double[] mRef) {
        String[] axes = {"x", "y", "z"};
        // r×f 各分量: x=ry fz-rz fy; y=rz fx-rx fz; z=rx fy-ry fx
        int[][] terms = {{1, 2, 2, 1}, {2, 0, 0, 2}, {0, 1, 1, 0}};
        List<Map<String, Object>> trace = new ArrayList<>();
        for (int axis = 0; axis < 3; axis++) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("component", "m" + axes[axis]);
            m.put("momentAtBalance", round0(mBal[axis]));
            List<Map<String, Object>> parts = new ArrayList<>();
            double t = 0;
            for (int k = 0; k < 2; k++) {
                int ri = terms[axis][k * 2];
                int fi = terms[axis][k * 2 + 1];
                double sign = k == 0 ? 1.0 : -1.0;
                double product = sign * r[ri] * f[fi];
                t += product;
                Map<String, Object> term = new LinkedHashMap<>();
                term.put("term", (k == 0 ? "+ " : "- ") + "r" + axes[ri] + " * F" + axes[fi]);
                term.put("r", round0(r[ri]));
                term.put("f", round0(f[fi]));
                term.put("sign", k == 0 ? "+" : "-");
                term.put("value", round0(product));
                parts.add(term);
            }
            m.put("crossTerms", parts);
            m.put("rCrossF", round0(t));
            m.put("momentAtRef", round0(mRef[axis]));
            trace.add(m);
        }
        return trace;
    }

    private static List<Map<String, Object>> coefficientTrace(boolean qOk, double q,
                                                               Calibration cal,
                                                               double[] fWind, double[] mWind) {
        String[] keys = {"Cd", "Cy", "CL", "Croll", "Cm", "Cn"};
        String[] kinds = {"force", "force", "force", "moment", "moment", "moment"};
        int[] idx = {0, 1, 2, 0, 1, 2};
        double[] numerators = {
                -fWind[0], fWind[1], -fWind[2],
                mWind[0], mWind[1], mWind[2]
        };
        String[] numExpr = {
                "-Fx_wind", "+Fy_wind", "-Fz_wind",
                "Mx_wind", "My_wind", "Mz_wind"
        };
        List<Map<String, Object>> trace = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", keys[i]);
            m.put("kind", kinds[i]);
            m.put("numeratorExpr", numExpr[i]);
            m.put("numeratorValue", round0(numerators[i]));
            m.put("windComponent", round0((i < 3 ? fWind : mWind)[idx[i]]));
            m.put("qPa", round0(q));
            if (kinds[i].equals("force")) {
                m.put("denomExpr", "q * S");
                m.put("denomValue", round0(q * cal.areaM2()));
                m.put("areaM2", cal.areaM2());
            } else {
                double len = keys[i].equals("Cm") ? cal.chordM() : cal.spanM();
                m.put("denomExpr", keys[i].equals("Cm") ? "q * S * cbar" : "q * S * b");
                m.put("denomValue", round0(q * cal.areaM2() * len));
                m.put("areaM2", cal.areaM2());
                m.put("lengthM", len);
            }
            m.put("generated", qOk);
            trace.add(m);
        }
        return trace;
    }

    private static Map<String, Object> vec(double[] v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("shape", new int[]{v.length});
        m.put("values", LinAlg.list(LinAlg.roundVec(v, OUT)));
        return m;
    }

    /** 漂移不可用时以 null 值表达，绝不写入 NaN/Infinity。 */
    private static Map<String, Object> nanVec(double[] v) {
        List<Double> values = new ArrayList<>(v.length);
        for (double ignored : v) {
            values.add(null);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("shape", new int[]{v.length});
        m.put("values", values);
        return m;
    }

    private static Map<String, Object> mat(double[][] a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("shape", new int[]{a.length, a[0].length});
        m.put("values", LinAlg.list(LinAlg.roundMat(a, OUT)));
        return m;
    }

    private static double round0(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            throw new IllegalArgumentException("输出中出现 NaN/Infinity");
        }
        return LinAlg.round(v, OUT);
    }

    private static String fmt(double v) {
        return String.valueOf(LinAlg.round(v, 3));
    }
}
