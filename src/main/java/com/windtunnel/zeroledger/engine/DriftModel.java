package com.windtunnel.zeroledger.engine;

import com.windtunnel.zeroledger.domain.Sample;

import java.util.List;

/**
 * 空载漂移拟合。输入是同一次 run 内“未被拒绝”的空载锨点，
 * 对六个通道分别按试验时间 t 拟合：
 *  - LINEAR：全部锨点最小二乘直线；包络外按直线延伸，但必须标 extrapolated；
 *  - SEGMENT：相邻锨点线性插补；包络外钳到最近端点（常量保持），同样标 extrapolated。
 *
 * 任何模式都只使用本 run 的锨点，绝不借用下一个 run。
 */
public final class DriftModel {

    public enum Mode { LINEAR, SEGMENT }

    /**
     * 单个通道的漂移。
     * LINEAR 用 intercept/slope 描述，knots 为包络两端点；
     * SEGMENT 用折点 (knotsT, knotsV) 描述，intercept/slope 为 NaN。
     */
    public record ChannelFit(double[] knotsT, double[] knotsV,
                             double intercept, double slope) {
        public boolean valid() {
            return knotsT != null && knotsT.length >= 2;
        }

        public double at(double t, Mode mode, boolean inside) {
            if (mode == Mode.LINEAR) {
                // 线性：包络内插补、包络外延伸（同一条直线）
                return intercept + slope * t;
            }
            // 分段：包络内线性插补；包络外钳到最近端点
            if (t <= knotsT[0]) {
                return knotsV[0];
            }
            if (t >= knotsT[knotsT.length - 1]) {
                return knotsV[knotsV.length - 1];
            }
            for (int i = 0; i < knotsT.length - 1; i++) {
                if (t >= knotsT[i] && t <= knotsT[i + 1]) {
                    double ratio = (t - knotsT[i]) / (knotsT[i + 1] - knotsT[i]);
                    return knotsV[i] + ratio * (knotsV[i + 1] - knotsV[i]);
                }
            }
            return Double.NaN;
        }
    }

    public record FitResult(ChannelFit[] channels, Mode mode,
                            double tMin, double tMax,
                            List<Sample> usedTares) {

        public boolean valid() {
            return usedTares.size() >= 2 && channels[0] != null;
        }

        /** t 是否落在锨点时间包络 [tMin, tMax] 内。 */
        public boolean insideEnvelope(double t) {
            return t >= tMin - 1e-9 && t <= tMax + 1e-9;
        }

        /** 六个通道在 t 处的漂移估计；可用锨点不足时返回 NaN 向量。 */
        public double[] valueAt(double t) {
            double[] d = new double[6];
            if (!valid()) {
                java.util.Arrays.fill(d, Double.NaN);
                return d;
            }
            boolean inside = insideEnvelope(t);
            for (int c = 0; c < 6; c++) {
                d[c] = channels[c].at(t, mode, inside);
            }
            return d;
        }
    }

    private DriftModel() {
    }

    public static FitResult fit(List<Sample> acceptedTares, Mode mode) {
        List<Sample> sorted = acceptedTares.stream()
                .sorted(java.util.Comparator.comparingDouble(Sample::t))
                .toList();
        if (sorted.size() < 2) {
            return new FitResult(new ChannelFit[6], mode,
                    Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, List.copyOf(sorted));
        }
        double tMin = sorted.get(0).t();
        double tMax = sorted.get(sorted.size() - 1).t();

        ChannelFit[] fits = new ChannelFit[6];
        for (int c = 0; c < 6; c++) {
            final int channel = c;
            double[] kt = sorted.stream().mapToDouble(Sample::t).toArray();
            double[] kv = sorted.stream().mapToDouble(smp -> smp.channels()[channel]).toArray();
            if (mode == Mode.SEGMENT) {
                fits[c] = new ChannelFit(kt, kv, Double.NaN, Double.NaN);
            } else {
                int n = sorted.size();
                double st = 0, sv = 0, stt = 0, stv = 0;
                for (Sample s : sorted) {
                    double t = s.t();
                    double v = s.channels()[c];
                    st += t;
                    sv += v;
                    stt += t * t;
                    stv += t * v;
                }
                double denom = n * stt - st * st;
                double slope = denom == 0 ? 0 : (n * stv - st * sv) / denom;
                double intercept = (sv - slope * st) / n;
                fits[c] = new ChannelFit(new double[]{tMin, tMax},
                        new double[]{intercept + slope * tMin, intercept + slope * tMax},
                        intercept, slope);
            }
        }
        return new FitResult(fits, mode, tMin, tMax, List.copyOf(sorted));
    }
}
