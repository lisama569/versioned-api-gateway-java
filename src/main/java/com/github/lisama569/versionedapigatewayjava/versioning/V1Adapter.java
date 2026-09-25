package com.github.lisama569.versionedapigatewayjava.versioning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lisama569.versionedapigatewayjava.error.ApiException;
import com.github.lisama569.versionedapigatewayjava.error.ErrorCode;
import com.github.lisama569.versionedapigatewayjava.model.InternalOrder;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * v1 适配规则：
 * customer -> customerId, item -> itemName, qty -> quantity；note 在 v1 中不存在，置 null。
 * 出现未知字段（如 v2 的 customerId/note）视为不兼容请求，拒绝。
 */
public class V1Adapter implements VersionAdapter {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> KNOWN_FIELDS = Set.of("customer", "item", "qty");

    @Override
    public String version() {
        return "v1";
    }

    @Override
    public InternalOrder adapt(String rawJson) {
        JsonNode node = parse(rawJson);
        rejectUnknownFields(node, KNOWN_FIELDS);
        String customer = requiredText(node, "customer");
        String item = requiredText(node, "item");
        int qty = requiredPositiveInt(node, "qty");
        return new InternalOrder(customer, item, qty, null);
    }

    static JsonNode parse(String rawJson) {
        try {
            JsonNode node = MAPPER.readTree(rawJson);
            if (node == null || !node.isObject()) {
                throw new ApiException(ErrorCode.INCOMPATIBLE_VERSION, "request body must be a JSON object");
            }
            return node;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(ErrorCode.INCOMPATIBLE_VERSION, "malformed JSON body");
        }
    }

    static void rejectUnknownFields(JsonNode node, Set<String> known) {
        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            String field = it.next().getKey();
            if (!known.contains(field)) {
                throw new ApiException(ErrorCode.INCOMPATIBLE_VERSION,
                        "field '" + field + "' is not part of this API version (renamed/deprecated/unknown)");
            }
        }
    }

    static String requiredText(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || !v.isTextual() || v.asText().isBlank()) {
            throw new ApiException(ErrorCode.MISSING_REQUIRED_FIELD, "missing required field: " + field);
        }
        return v.asText();
    }

    static int requiredPositiveInt(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || !v.isInt() || v.asInt() <= 0) {
            throw new ApiException(ErrorCode.MISSING_REQUIRED_FIELD,
                    "missing or invalid required field: " + field + " (must be positive integer)");
        }
        return v.asInt();
    }
}
