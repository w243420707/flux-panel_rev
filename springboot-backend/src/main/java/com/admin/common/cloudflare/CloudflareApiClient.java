package com.admin.common.cloudflare;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import javax.annotation.Resource;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class CloudflareApiClient {

    private static final String API_BASE = "https://api.cloudflare.com/client/v4";
    private static final int DNS_RECORD_PAGE_SIZE = 100;

    @Resource
    private RestTemplate restTemplate;

    public List<CloudflareDnsRecord> listDnsRecords(String zoneId, String apiToken, String name, String type) {
        List<CloudflareDnsRecord> records = new ArrayList<>();
        int page = 1;
        while (true) {
            JSONObject response = request(apiToken, buildListUrl(zoneId, name, type, page, DNS_RECORD_PAGE_SIZE), HttpMethod.GET, null);
            JSONArray result = response.getJSONArray("result");
            if (result != null && !result.isEmpty()) {
                records.addAll(JSON.parseArray(result.toJSONString(), CloudflareDnsRecord.class));
            }

            JSONObject resultInfo = response.getJSONObject("result_info");
            int totalPages = resultInfo == null ? page : resultInfo.getIntValue("total_pages");
            if (page >= totalPages || result == null || result.size() < DNS_RECORD_PAGE_SIZE) {
                break;
            }
            page++;
        }
        return records;
    }

    public CloudflareDnsRecord createDnsRecord(String zoneId, String apiToken, CloudflareDnsRecord record) {
        JSONObject payload = new JSONObject();
        payload.put("type", record.getType());
        payload.put("name", record.getName());
        payload.put("content", record.getContent());
        payload.put("ttl", record.getTtl());
        payload.put("proxied", record.getProxied());
        if (record.getComment() != null) {
            payload.put("comment", record.getComment());
        }
        JSONObject response = request(apiToken, buildRecordUrl(zoneId), HttpMethod.POST, payload);
        return JSON.toJavaObject(response.getJSONObject("result"), CloudflareDnsRecord.class);
    }

    public CloudflareDnsRecord updateDnsRecord(String zoneId, String apiToken, String recordId, CloudflareDnsRecord record) {
        JSONObject payload = new JSONObject();
        payload.put("type", record.getType());
        payload.put("name", record.getName());
        payload.put("content", record.getContent());
        payload.put("ttl", record.getTtl());
        payload.put("proxied", record.getProxied());
        if (record.getComment() != null) {
            payload.put("comment", record.getComment());
        }
        JSONObject response = request(apiToken, buildRecordUrl(zoneId, recordId), HttpMethod.PUT, payload);
        return JSON.toJavaObject(response.getJSONObject("result"), CloudflareDnsRecord.class);
    }

    public void deleteDnsRecord(String zoneId, String apiToken, String recordId) {
        request(apiToken, buildRecordUrl(zoneId, recordId), HttpMethod.DELETE, null);
    }

    public JSONObject getZone(String zoneId, String apiToken) {
        return request(apiToken, API_BASE + "/zones/" + zoneId, HttpMethod.GET, null);
    }

    private JSONObject request(String apiToken, String url, HttpMethod method, JSONObject payload) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(apiToken);
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));

            HttpEntity<String> entity = new HttpEntity<>(payload == null ? null : payload.toJSONString(), headers);
            ResponseEntity<String> response = restTemplate.exchange(URI.create(url), method, entity, String.class);
            JSONObject json = JSON.parseObject(response.getBody() == null ? "{}" : response.getBody());
            ensureSuccess(json, url);
            return json;
        } catch (RestClientException e) {
            throw new IllegalStateException("Cloudflare API request failed: " + e.getMessage(), e);
        }
    }

    private void ensureSuccess(JSONObject response, String url) {
        if (response == null) {
            throw new IllegalStateException("Cloudflare API returned empty body: " + url);
        }
        if (response.getBooleanValue("success")) {
            return;
        }
        JSONArray errors = response.getJSONArray("errors");
        StringBuilder builder = new StringBuilder("Cloudflare API error");
        if (errors != null && !errors.isEmpty()) {
            builder.append(": ");
            for (int i = 0; i < errors.size(); i++) {
                JSONObject error = errors.getJSONObject(i);
                if (i > 0) {
                    builder.append(" | ");
                }
                builder.append(error.getString("message"));
            }
        }
        throw new IllegalStateException(builder.toString());
    }

    private String buildListUrl(String zoneId, String name, String type, int page, int perPage) {
        StringBuilder url = new StringBuilder(API_BASE)
                .append("/zones/")
                .append(zoneId)
                .append("/dns_records");
        List<String> params = new ArrayList<>();
        params.add("page=" + page);
        params.add("per_page=" + perPage);
        if (name != null && !name.isBlank()) {
            params.add("name=" + encode(name));
        }
        if (type != null && !type.isBlank()) {
            params.add("type=" + encode(type));
        }
        if (!params.isEmpty()) {
            url.append("?").append(String.join("&", params));
        }
        return url.toString();
    }

    private String buildRecordUrl(String zoneId) {
        return API_BASE + "/zones/" + zoneId + "/dns_records";
    }

    private String buildRecordUrl(String zoneId, String recordId) {
        return buildRecordUrl(zoneId) + "/" + recordId;
    }

    private String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
