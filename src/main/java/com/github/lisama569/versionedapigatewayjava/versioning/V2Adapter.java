package com.github.lisama569.versionedapigatewayjava.versioning;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.lisama569.versionedapigatewayjava.error.ApiException;
import com.github.lisama569.versionedapigatewayjava.error.ErrorCode;
import com.github.lisama569.versionedapigatewayjava.model.InternalOrder;
import java.util.Map;
import java.util.Set;

/**
 * v2 适配规则：
 * 字段重命名 customerId/itemName/quantity；新增可选 note。
 * v1 旧字段名（customer/item/qty）在 v2 中已废弃，出现即拒绝并提示迁移。
 */
public class V2Adapter implements VersionAdapter {
    private static final Set<String> KNOWN_FIELDS = Set.of("customerId", "itemName", "quantity", "note");
    private static final Map<String, String> DEPRECATED_RENAMES = Map.of(
            "customer", "customerId",
            "item", "itemName",
            "qty", "quantity");

    @Override
    public String version() {
        return "v2";
    }

    @Override
    public InternalOrder adapt(String rawJson) {
        JsonNode node = V1Adapter.parse(rawJson);
        rejectDeprecatedFields(node);
        V1Adapter.rejectUnknownFields(node, KNOWN_FIELDS);
        String customerId = V1Adapter.requiredText(node, "customerId");
        String itemName = V1Adapter.requiredText(node, "itemName");
        int quantity = V1Adapter.requiredPositiveInt(node, "quantity");
        JsonNode noteNode = node.get("note");
        String note = (noteNode != null && noteNode.isTextual()) ? noteNode.asText() : null;
        return new InternalOrder(customerId, itemName, quantity, note);
    }

    private void rejectDeprecatedFields(JsonNode node) {
        for (Map.Entry<String, String> e : DEPRECATED_RENAMES.entrySet()) {
            if (node.has(e.getKey())) {
                throw new ApiException(ErrorCode.INCOMPATIBLE_VERSION,
                        "field '" + e.getKey() + "' was renamed to '" + e.getValue() + "' in v2");
            }
        }
    }
}
