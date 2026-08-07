package com.yadony.api.config;

import com.google.firebase.auth.FirebaseAuth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ConfigControllerSmsEnabledTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private FirebaseAuth firebaseAuth;

    @Test
    void smsEnabledIsPublicAndReturnsConfiguredValue() throws Exception {
        mockMvc.perform(get("/config/sms-enabled"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled", notNullValue()));
    }

    @Test
    void smsEnabledDefaultsToFalseInTestProfile() throws Exception {
        mockMvc.perform(get("/config/sms-enabled"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
    }
}
