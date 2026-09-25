package com.github.lisama569.versionedapigatewayjava.versioning;

import com.github.lisama569.versionedapigatewayjava.model.InternalOrder;

/** 单版本适配器：把该版本原始 JSON 映射为统一内部模型。 */
public interface VersionAdapter {
    String version();

    InternalOrder adapt(String rawJson);
}
