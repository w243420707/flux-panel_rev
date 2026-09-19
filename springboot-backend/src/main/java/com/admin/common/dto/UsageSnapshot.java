package com.admin.common.dto;

import com.alibaba.fastjson.JSONObject;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.UUID;

/** An immutable, validated sample from a node's durable meter. Boot/IP never identify a VPS. */
public record UsageSnapshot(String hardwareId, String systemId, String macId, String installationId,
                            String meterEpoch, long sequence, long uploadBytes, long downloadBytes) {
    public UsageSnapshot {
        hardwareId = hashOrEmpty(hardwareId);
        systemId = hashOrEmpty(systemId);
        macId = hashOrEmpty(macId);
        installationId = uuidOrEmpty(installationId);
        meterEpoch = uuidOrEmpty(meterEpoch);
        if (meterEpoch.isEmpty()) throw new IllegalArgumentException("节点流量计量标识无效");
        if (sequence < 0 || uploadBytes < 0 || downloadBytes < 0) {
            throw new IllegalArgumentException("节点流量计量值必须为非负整数");
        }
    }

    public static UsageSnapshot from(JSONObject value) {
        if (value == null) throw new IllegalArgumentException("节点尚未提供 VPS 累计统计，请更新节点");
        return new UsageSnapshot(value.getString("hardware_id"), value.getString("system_id"),
                value.getString("mac_id"), value.getString("installation_id"), value.getString("meter_epoch"),
                nonnegativeInteger(value, "sequence"), nonnegativeInteger(value, "upload_bytes"),
                nonnegativeInteger(value, "download_bytes"));
    }

    public boolean hasStrongIdentity() {
        return !hardwareId.isEmpty() || !systemId.isEmpty();
    }

    private static long nonnegativeInteger(JSONObject value, String field) {
        Object raw = value.get(field);
        if (raw == null || raw instanceof Boolean) throw new IllegalArgumentException("节点流量计量数据不完整");
        try {
            long number = new BigDecimal(raw.toString()).longValueExact();
            if (number < 0) throw new ArithmeticException("negative");
            return number;
        } catch (NumberFormatException | ArithmeticException e) {
            throw new IllegalArgumentException("节点流量计量值必须为有效的非负整数");
        }
    }

    private static String hashOrEmpty(String value) {
        if (value == null) return "";
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.matches("[0-9a-f]{64}") ? normalized : "";
    }

    private static String uuidOrEmpty(String value) {
        if (value == null || !value.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            return "";
        }
        return UUID.fromString(value).toString();
    }
}
