package com.dony.api.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ConfigControllerIT {

    @Autowired MockMvc mockMvc;

    @Test
    void getCommissionRate_returnsConfiguredRate() throws Exception {
        mockMvc.perform(get("/config/commission-rate"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.rate").value(0.12));
    }

    @Test
    void getCommissionRate_isPublic_noAuthRequired() throws Exception {
        // No authentication header — should still return 200
        mockMvc.perform(get("/config/commission-rate"))
            .andExpect(status().isOk());
    }
}
