package com.example.zeroledger.domain;

import java.util.Locale;

public final class LinearAlgebra {
    private LinearAlgebra() {
    }

    public static double[] vector(double... values) {
        return values.clone();
    }

    public static double[][] identity(int size) {
        double[][] result = new double[size][size];
        for (int i = 0; i < size; i++) {
            result[i][i] = 1.0;
        }
        return result;
    }

    public static double[] multiply(double[][] matrix, double[] vector) {
        double[] result = new double[matrix.length];
        for (int i = 0; i < matrix.length; i++) {
            double sum = 0.0;
            for (int j = 0; j < vector.length; j++) {
                sum += matrix[i][j] * vector[j];
            }
            result[i] = sum;
        }
        return result;
    }

    public static double[][] multiply(double[][] left, double[][] right) {
        double[][] result = new double[left.length][right[0].length];
        for (int i = 0; i < left.length; i++) {
            for (int j = 0; j < right[0].length; j++) {
                double sum = 0.0;
                for (int k = 0; k < right.length; k++) {
                    sum += left[i][k] * right[k][j];
                }
                result[i][j] = sum;
            }
        }
        return result;
    }

    public static double[][] rotationX(double radians) {
        double c = Math.cos(radians);
        double s = Math.sin(radians);
        return new double[][]{
                {1, 0, 0},
                {0, c, -s},
                {0, s, c}
        };
    }

    public static double[][] rotationY(double radians) {
        double c = Math.cos(radians);
        double s = Math.sin(radians);
        return new double[][]{
                {c, 0, s},
                {0, 1, 0},
                {-s, 0, c}
        };
    }

    public static double[][] rotationZ(double radians) {
        double c = Math.cos(radians);
        double s = Math.sin(radians);
        return new double[][]{
                {c, -s, 0},
                {s, c, 0},
                {0, 0, 1}
        };
    }

    public static double[] cross(double[] left, double[] right) {
        return new double[]{
                left[1] * right[2] - left[2] * right[1],
                left[2] * right[0] - left[0] * right[2],
                left[0] * right[1] - left[1] * right[0]
        };
    }

    public static double[][] crossMatrix(double[] r) {
        return new double[][]{
                {0, -r[2], r[1]},
                {r[2], 0, -r[0]},
                {-r[1], r[0], 0}
        };
    }

    public static Double finiteOrNull(double value) {
        return Double.isFinite(value) ? value : null;
    }

    public static String format(double value) {
        if (!Double.isFinite(value)) {
            return "—";
        }
        return String.format(Locale.US, "%.6g", value);
    }
}
