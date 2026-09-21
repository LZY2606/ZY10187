package com.windtunnel.zeroledger.domain;

/** 领域记录：一次 run 的配置。 */
public record RunSettings(long runId, String name, String driftMode,
                          String rotationOrder, String calibVersion) {
}
