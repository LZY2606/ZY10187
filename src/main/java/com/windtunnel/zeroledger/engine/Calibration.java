package com.windtunnel.zeroledger.engine;

/**
 * 一套天平标定版本：
 * K      —— 6x6 增益矩阵，把六通道电测值映射到天平坐标 [Fx,Fy,Fz,Mx,My,Mz]；
 * mount  —— 3x3 安装矩阵，把天平坐标映射到模型坐标（力与力矩共用）；
 * rRef   —— 模型力矩参考中心相对天平中心的位置（米）。
 */
public record Calibration(String code, String label,
                          double[][] k, double[][] mount, double[] rRef,
                          double areaM2, double spanM, double chordM) {

    public Calibration {
        if (k.length != 6 || k[0].length != 6) {
            throw new IllegalArgumentException("K 必须是 6x6");
        }
        if (mount.length != 3 || mount[0].length != 3) {
            throw new IllegalArgumentException("mount 必须是 3x3");
        }
        if (rRef.length != 3) {
            throw new IllegalArgumentException("rRef 必须是 3 维");
        }
        if (areaM2 <= 0 || spanM <= 0 || chordM <= 0) {
            throw new IllegalArgumentException("参考面积/长度必须为正");
        }
    }
}
