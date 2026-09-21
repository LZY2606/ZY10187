package com.windtunnel.zeroledger.domain;

import java.util.List;

/** 一个记录点：TARE=空载锨点，TEST=风载测点。channels 为天平六通道原值 c1..c6。 */
public record Sample(Long id, long runId, double t, String kind,
                     double[] channels, double alphaDeg, double betaDeg,
                     double qPa, double tempK, boolean tareExcluded) {

    public List<Double> channelList() {
        return List.of(channels[0], channels[1], channels[2], channels[3], channels[4], channels[5]);
    }
}
