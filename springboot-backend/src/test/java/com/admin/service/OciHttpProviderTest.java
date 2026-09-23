package com.admin.service;

import com.oracle.bmc.http.client.HttpProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class OciHttpProviderTest {
    @Test
    void defaultOciHttpProviderCanBeLoadedInSpringBootRuntimeDependencies() {
        HttpProvider provider = HttpProvider.getDefault();

        assertNotNull(provider);
        assertNotNull(provider.newBuilder());
    }
}
