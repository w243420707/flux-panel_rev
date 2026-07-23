package com.admin.common.utils;

import com.admin.entity.Tunnel;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class TunnelNodeUtil {

    private TunnelNodeUtil() {
    }

    public static List<Long> normalizeNodeIds(List<Long> nodeIds, Long fallbackNodeId) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        if (nodeIds != null) {
            for (Long id : nodeIds) {
                if (id != null && id > 0) {
                    ids.add(id);
                }
            }
        }
        if (ids.isEmpty() && fallbackNodeId != null && fallbackNodeId > 0) {
            ids.add(fallbackNodeId);
        }
        return new ArrayList<>(ids);
    }

    public static List<Long> getInNodeIds(Tunnel tunnel) {
        if (tunnel == null) {
            return new ArrayList<>();
        }
        return parseNodeIds(tunnel.getInNodeIds(), tunnel.getInNodeId());
    }

    public static List<Long> getOutNodeIds(Tunnel tunnel) {
        if (tunnel == null) {
            return new ArrayList<>();
        }
        return parseNodeIds(tunnel.getOutNodeIds(), tunnel.getOutNodeId());
    }

    public static boolean containsInNode(Tunnel tunnel, Long nodeId) {
        return nodeId != null && getInNodeIds(tunnel).contains(nodeId);
    }

    public static boolean containsOutNode(Tunnel tunnel, Long nodeId) {
        return nodeId != null && getOutNodeIds(tunnel).contains(nodeId);
    }

    public static List<Long> parseNodeIds(String raw) {
        return parseNodeIds(raw, null);
    }

    public static String toJsonArray(List<Long> nodeIds) {
        List<Long> ids = normalizeNodeIds(nodeIds, null);
        return ids.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(",", "[", "]"));
    }

    private static List<Long> parseNodeIds(String raw, Long fallbackNodeId) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        if (StringUtils.isNotBlank(raw)) {
            try {
                JSONArray array = JSON.parseArray(raw);
                if (array != null) {
                    for (Object value : array) {
                        Long id = toLong(value);
                        if (id != null && id > 0) {
                            ids.add(id);
                        }
                    }
                }
            } catch (Exception ignored) {
                parseLooseList(raw, ids);
            }
        }

        if (ids.isEmpty() && fallbackNodeId != null && fallbackNodeId > 0) {
            ids.add(fallbackNodeId);
        }
        return new ArrayList<>(ids);
    }

    private static void parseLooseList(String raw, Set<Long> ids) {
        String cleaned = raw.replace("[", "")
                .replace("]", "")
                .replace("\"", "")
                .replace("'", "");
        for (String part : cleaned.split(",")) {
            Long id = toLong(part.trim());
            if (id != null && id > 0) {
                ids.add(id);
            }
        }
    }

    private static Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            String text = value.toString().trim();
            if (text.isEmpty()) {
                return null;
            }
            return Long.parseLong(text);
        } catch (Exception ignored) {
            return null;
        }
    }
}
