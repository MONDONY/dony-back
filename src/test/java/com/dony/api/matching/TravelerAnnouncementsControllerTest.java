package com.dony.api.matching;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class TravelerAnnouncementsControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean AnnouncementService announcementService;

    @Test
    void travelerAnnouncements_isPublic_returns200() throws Exception {
        when(announcementService.getTravelerAnnouncements(any())).thenReturn(java.util.List.of());
        mockMvc.perform(get("/travelers/{id}/announcements", UUID.randomUUID()))
            .andExpect(status().isOk());
    }
}
