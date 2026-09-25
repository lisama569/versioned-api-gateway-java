package com.github.lisama569.versionedapigatewayjava.versioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.lisama569.versionedapigatewayjava.error.ApiException;
import com.github.lisama569.versionedapigatewayjava.error.ErrorCode;
import com.github.lisama569.versionedapigatewayjava.model.InternalOrder;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 跨版本兼容：字段重命名/新增映射到统一模型；缺失必填与不兼容请求给出可区分原因。 */
class VersionCompatibilityTest {
    private static final Logger log = LoggerFactory.getLogger(VersionCompatibilityTest.class);
    private final VersionRouter router = new VersionRouter();

    @Test
    void v1AndV2MapToSameInternalModel() {
        String requestId = UUID.randomUUID().toString();
        String v1Json = "{\"customer\":\"cust-1\",\"item\":\"book\",\"qty\":2}";
        String v2Json = "{\"customerId\":\"cust-1\",\"itemName\":\"book\",\"quantity\":2,\"note\":\"gift\"}";

        InternalOrder fromV1 = router.resolve("v1").adapt(v1Json);
        InternalOrder fromV2 = router.resolve("v2").adapt(v2Json);

        assertThat(fromV1.customerId()).isEqualTo("cust-1");
        assertThat(fromV1.itemName()).isEqualTo("book");
        assertThat(fromV1.quantity()).isEqualTo(2);
        assertThat(fromV1.note()).isNull();
        assertThat(fromV2)
                .usingRecursiveComparison().ignoringFields("note")
                .isEqualTo(fromV1);
        assertThat(fromV2.note()).isEqualTo("gift");
        log.info("[{}] matched rule=v1->internal and v2->internal basis='customer=customerId,item=itemName,qty=quantity; note=v2-only' fromV1={} fromV2={}",
                requestId, fromV1, fromV2);
    }

    @Test
    void missingRequiredFieldIsRejectedWithDistinctCode() {
        String requestId = UUID.randomUUID().toString();
        assertThatThrownBy(() -> router.resolve("v2")
                .adapt("{\"itemName\":\"book\",\"quantity\":1}"))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.code()).isEqualTo(ErrorCode.MISSING_REQUIRED_FIELD));
        assertThatThrownBy(() -> router.resolve("v1").adapt("{\"customer\":\"c\",\"item\":\"i\",\"qty\":0}"))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.code()).isEqualTo(ErrorCode.MISSING_REQUIRED_FIELD));
        log.info("[{}] matched rule=required-validation basis='missing customerId / non-positive qty rejected with MISSING_REQUIRED_FIELD'",
                requestId);
    }

    @Test
    void v2UsingV1FieldNamesIsIncompatible() {
        String requestId = UUID.randomUUID().toString();
        assertThatThrownBy(() -> router.resolve("v2")
                .adapt("{\"customer\":\"c\",\"item\":\"i\",\"quantity\":1}"))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ErrorCode.INCOMPATIBLE_VERSION);
                    assertThat(ex.getMessage()).contains("renamed");
                });
        log.info("[{}] matched rule=deprecated-field basis='v1 field names removed in v2 -> INCOMPATIBLE_VERSION'", requestId);
    }

    @Test
    void v1RejectsForwardOnlyFields() {
        String requestId = UUID.randomUUID().toString();
        assertThatThrownBy(() -> router.resolve("v1")
                .adapt("{\"customer\":\"c\",\"item\":\"i\",\"qty\":1,\"note\":\"x\"}"))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.code()).isEqualTo(ErrorCode.INCOMPATIBLE_VERSION));
        log.info("[{}] matched rule=unknown-field basis='v2-only note sent to v1 -> INCOMPATIBLE_VERSION'", requestId);
    }

    @Test
    void unknownOrMissingVersionIsUnsupported() {
        String requestId = UUID.randomUUID().toString();
        assertThatThrownBy(() -> router.resolve("v9"))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION));
        assertThatThrownBy(() -> router.resolve(null))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION));
        log.info("[{}] matched rule=version-routing basis='v9/null -> UNSUPPORTED_VERSION, supported=[v1,v2]'", requestId);
    }

    @Test
    void malformedBodyIsIncompatible() {
        String requestId = UUID.randomUUID().toString();
        assertThatThrownBy(() -> router.resolve("v1").adapt("not-json"))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.code()).isEqualTo(ErrorCode.INCOMPATIBLE_VERSION));
        log.info("[{}] matched rule=body-parse basis='unparsable JSON -> INCOMPATIBLE_VERSION'", requestId);
    }
}
