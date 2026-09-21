package com.windtunnel.zeroledger.engine;

import java.util.ArrayList;
import java.util.List;

/** 纯函数线性代数：保留全部中间矩阵与向量，供页面逐项回查。 */
public final class LinAlg {

    private LinAlg() {
    }

    public static double[] vec(double... v) {
        return v;
    }

    public static double[] matVec(double[][] a, double[] x) {
        int m = a.length;
        int n = a[0].length;
        if (x.length != n) {
            throw new IllegalArgumentException("矩阵/向量维度不符: " + m + "x" + n + " vs " + x.length);
        }
        double[] y = new double[m];
        for (int i = 0; i < m; i++) {
            double sum = 0;
            for (int j = 0; j < n; j++) {
                sum += a[i][j] * x[j];
            }
            y[i] = sum;
        }
        y[0] += 0.0; // 保留 -0.0 也无妨，渲染时统一规范化
        return y;
    }

    public static double[][] matMul(double[][] a, double[][] b) {
        int m = a.length;
        int k = a[0].length;
        int n = b[0].length;
        if (b.length != k) {
            throw new IllegalArgumentException("矩阵乘法维度不符");
        }
        double[][] c = new double[m][n];
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < n; j++) {
                double sum = 0;
                for (int p = 0; p < k; p++) {
                    sum += a[i][p] * b[p][j];
                }
                c[i][j] = sum;
            }
        }
        return c;
    }

    /** 三维叉积 r × f（力矩平移唯一允许的算法）。 */
    public static double[] cross3(double[] r, double[] f) {
        if (r.length != 3 || f.length != 3) {
            throw new IllegalArgumentException("叉积要求两个 3 维向量");
        }
        return new double[]{
                r[1] * f[2] - r[2] * f[1],
                r[2] * f[0] - r[0] * f[2],
                r[0] * f[1] - r[1] * f[0]
        };
    }

    public static double[] add(double[] a, double[] b) {
        double[] c = new double[a.length];
        for (int i = 0; i < a.length; i++) {
            c[i] = a[i] + b[i];
        }
        return c;
    }

    public static double[] sub(double[] a, double[] b) {
        double[] c = new double[a.length];
        for (int i = 0; i < a.length; i++) {
            c[i] = a[i] - b[i];
        }
        return c;
    }

    public static double norm(double[] a) {
        double s = 0;
        for (double v : a) {
            s += v * v;
        }
        return Math.sqrt(s);
    }

    /**
     * 绕模型 y 轴的主动旋转矩阵（右手定则，右手法向 +y）。
     * 模型坐标：x 机头、y 左翼、z 上（右手系）。
     */
    public static double[][] rotY(double theta) {
        double c = Math.cos(theta);
        double s = Math.sin(theta);
        return new double[][]{
                {c, 0, s},
                {0, 1, 0},
                {-s, 0, c}
        };
    }

    /** 绕模型 z 轴的主动旋转矩阵（右手定则，右手法向 +z）。 */
    public static double[][] rotZ(double theta) {
        double c = Math.cos(theta);
        double s = Math.sin(theta);
        return new double[][]{
                {c, -s, 0},
                {s, c, 0},
                {0, 0, 1}
        };
    }

    /** 单位阵。 */
    public static double[][] identity(int n) {
        double[][] i = new double[n][n];
        for (int k = 0; k < n; k++) {
            i[k][k] = 1;
        }
        return i;
    }

    /** 四舍五入到给定小数位（仅用于输出 JSON；计算始终使用 double 全精度）。 */
    public static double round(double v, int digits) {
        double scale = Math.pow(10, digits);
        double r = Math.rint(v * scale) / scale;
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return v;
        }
        return r == 0.0 ? 0.0 : r;
    }

    public static double[] roundVec(double[] v, int digits) {
        double[] r = new double[v.length];
        for (int i = 0; i < v.length; i++) {
            r[i] = round(v[i], digits);
        }
        return r;
    }

    public static double[][] roundMat(double[][] m, int digits) {
        double[][] r = new double[m.length][];
        for (int i = 0; i < m.length; i++) {
            r[i] = roundVec(m[i], digits);
        }
        return r;
    }

    public static List<Double> list(double[] v) {
        List<Double> l = new ArrayList<>(v.length);
        for (double d : v) {
            l.add(d);
        }
        return l;
    }

    public static List<List<Double>> list(double[][] m) {
        List<List<Double>> l = new ArrayList<>(m.length);
        for (double[] row : m) {
            l.add(list(row));
        }
        return l;
    }
}
