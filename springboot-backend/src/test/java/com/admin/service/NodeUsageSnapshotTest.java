package com.admin.service;

import com.admin.common.dto.NodeUsageConfirmDto;
import com.admin.common.dto.UsageSnapshot;
import com.alibaba.fastjson.JSONObject;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class NodeUsageSnapshotTest {
    @Test
    void parsesTheActualSnakeCaseProtocolWithoutTreatingBootIdAsMachineIdentity() {
        JSONObject json = base();
        json.put("hardware_id", "A".repeat(64));
        json.put("system_id", "b".repeat(64));
        json.put("boot_id", "different-on-every-reboot");
        json.put("upload_bytes", 9_007_199_254_740_993L);

        UsageSnapshot snapshot = UsageSnapshot.from(json);

        assertEquals("a".repeat(64), snapshot.hardwareId());
        assertEquals(9_007_199_254_740_993L, snapshot.uploadBytes());
        assertEquals(2, snapshot.downloadBytes());
        assertTrue(snapshot.hasStrongIdentity());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.5", "-1", "9223372036854775808", "NaN", "true"})
    void rejectsFractionalNegativeOverflowAndNonNumericCounters(String value) {
        JSONObject json = base();
        json.put("sequence", value);
        assertThrows(IllegalArgumentException.class, () -> UsageSnapshot.from(json));
    }

    @Test
    void absentOrInvalidMeterStateIsRejectedAndInvalidFingerprintsAreNotTrusted() {
        JSONObject json = base();
        json.put("hardware_id", "provider-default");
        assertFalse(UsageSnapshot.from(json).hasStrongIdentity());
        json.remove("upload_bytes");
        assertThrows(IllegalArgumentException.class, () -> UsageSnapshot.from(json));
        JSONObject invalidEpoch = base();
        invalidEpoch.put("meter_epoch", "not-a-uuid");
        assertThrows(IllegalArgumentException.class, () -> UsageSnapshot.from(invalidEpoch));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.5", "9223372036854775808"})
    void confirmationRecordIdCannotBeTruncatedIntoAnotherRecord(String id) {
        ObjectMapper mapper = new ObjectMapper();
        String json = "{\"id\":7,\"expectedUsageId\":" + id + ",\"candidateId\":\"candidate\",\"decision\":\"same\"}";
        assertThrows(Exception.class, () -> mapper.readValue(json, NodeUsageConfirmDto.class));
    }

    private static JSONObject base() {
        JSONObject json = new JSONObject();
        json.put("meter_epoch", "aaaaaaaa-1111-2222-3333-000000000001");
        json.put("installation_id", "aaaaaaaa-1111-2222-3333-000000000002");
        json.put("sequence", 1);
        json.put("upload_bytes", 1);
        json.put("download_bytes", 2);
        return json;
    }
}
