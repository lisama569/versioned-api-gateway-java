package com.github.lisama569.versionedapigatewayjava.upstream;

import com.github.lisama569.versionedapigatewayjava.common.model.InternalOrder;

/** 上游依赖接口；生产/测试均使用本地模拟实现，无真实网络。 */
public interface UpstreamClient {
    UpstreamResponse invoke(InternalOrder order);
}
