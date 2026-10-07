package com.github.lisama569.versionedapigatewayjava.circuitbreaker;

/** 熔断器状态：关闭（正常放行）/ 打开（快速失败或降级）/ 半开（限量试探）。 */
public enum CircuitState {
    CLOSED,
    OPEN,
    HALF_OPEN
}
