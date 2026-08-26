package com.admin;

import com.admin.common.dto.SiteTrafficDto;
import com.admin.common.dto.UserPackageDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SiteTrafficPackageDtoTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void siteTrafficReportsBothDirectionsAndTotal() {
        SiteTrafficDto siteTraffic = new SiteTrafficDto(12L, 30L);

        assertEquals(12L, siteTraffic.getTotalInFlow());
        assertEquals(30L, siteTraffic.getTotalOutFlow());
        assertEquals(42L, siteTraffic.getTotalFlow());
    }

    @Test
    void siteTrafficIsOmittedWhenNotSet() throws Exception {
        UserPackageDto packageDto = new UserPackageDto();

        String json = objectMapper.writeValueAsString(packageDto);

        assertFalse(json.contains("siteTraffic"));
    }

    @Test
    void siteTrafficIsSerializedForAdminPackage() throws Exception {
        UserPackageDto packageDto = new UserPackageDto();
        packageDto.setSiteTraffic(new SiteTrafficDto(12L, 30L));

        String json = objectMapper.writeValueAsString(packageDto);

        assertTrue(json.contains("\"siteTraffic\""));
        assertTrue(json.contains("\"totalInFlow\":12"));
        assertTrue(json.contains("\"totalOutFlow\":30"));
        assertTrue(json.contains("\"totalFlow\":42"));
    }
}
