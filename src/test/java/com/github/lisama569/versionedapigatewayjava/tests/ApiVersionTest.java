package com.github.lisama569.versionedapigatewayjava.tests;

import com.github.lisama569.versionedapigatewayjava.version.ApiVersion;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 版本头解析：大小写、空白、非法值、缺失均确定性处理，不依赖网络。
 */
class ApiVersionTest {

    private static final Logger log = LoggerFactory.getLogger(ApiVersionTest.class);

    @Test
    void parsesSupportedVersionsCaseInsensitively() {
        for (String raw : new String[]{"v1", "V2", " v3 "}) {
            Optional<ApiVersion> v = ApiVersion.fromHeader(raw);
            assertTrue(v.isPresent(), "raw=" + raw);
            log.info("test=ApiVersion rule=VERSION_PARSE basis=header={} parsed={}", raw, v.get());
        }
        assertEquals(ApiVersion.V1, ApiVersion.fromHeader("v1").orElseThrow());
        assertEquals(ApiVersion.V2, ApiVersion.fromHeader("V2").orElseThrow());
        assertEquals(ApiVersion.V3, ApiVersion.fromHeader(" v3 ").orElseThrow());
    }

    @Test
    void rejectsUnknownOrMissingVersion() {
        for (String raw : new String[]{null, "", "  ", "v4", "1", "abc"}) {
            assertTrue(ApiVersion.fromHeader(raw).isEmpty(), "raw=" + raw);
            log.info("test=ApiVersion rule=VERSION_UNSUPPORTED basis=header={} -> empty", raw);
        }
    }
}
