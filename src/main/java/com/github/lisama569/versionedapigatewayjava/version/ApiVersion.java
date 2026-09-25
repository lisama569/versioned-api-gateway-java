package com.github.lisama569.versionedapigatewayjava.version;

import java.util.Arrays;
import java.util.Optional;

/**
 * 受支持的 API 版本。
 */
public enum ApiVersion {
    V1, V2, V3;

    /**
     * 解析 X-API-Version 头。接受 v1/v2/v3（大小写不敏感）；
     * 缺失、空白或无法识别时返回 empty，由上层按 VERSION_UNSUPPORTED 拒绝。
     */
    public static Optional<ApiVersion> fromHeader(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String normalized = raw.trim().toLowerCase();
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        if (!normalized.startsWith("v")) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(v -> v.name().toLowerCase().equals(normalized))
                .findFirst();
    }

    public String displayName() {
        return name().toLowerCase();
    }
}
