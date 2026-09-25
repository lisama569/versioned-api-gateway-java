package com.github.lisama569.versionedapigatewayjava.versioning;

import com.github.lisama569.versionedapigatewayjava.error.ApiException;
import com.github.lisama569.versionedapigatewayjava.error.ErrorCode;
import java.util.Map;
import java.util.Set;

/** 按版本号选择适配器；缺失或未知版本拒绝并给出可区分原因。 */
public class VersionRouter {
    private final Map<String, VersionAdapter> adapters;

    public VersionRouter() {
        this(Map.of("v1", new V1Adapter(), "v2", new V2Adapter()));
    }

    public VersionRouter(Map<String, VersionAdapter> adapters) {
        this.adapters = Map.copyOf(adapters);
    }

    public VersionAdapter resolve(String version) {
        if (version == null || version.isBlank()) {
            throw new ApiException(ErrorCode.UNSUPPORTED_VERSION,
                    "missing API version; supported: " + supportedVersions());
        }
        VersionAdapter adapter = adapters.get(version.trim());
        if (adapter == null) {
            throw new ApiException(ErrorCode.UNSUPPORTED_VERSION,
                    "unsupported API version '" + version + "'; supported: " + supportedVersions());
        }
        return adapter;
    }

    public Set<String> supportedVersions() {
        return adapters.keySet();
    }
}
