package com.dony.api.addressbook.recipient;

import com.dony.api.auth.KycStatus;
import com.dony.api.auth.Role;
import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.auth.UserStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class RecipientControllerIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired RecipientRepository recipientRepository;
    @Autowired UserRepository userRepository;

    private static final String SENDER_UID = "firebase-recipient-sender";
    private static final String OTHER_UID  = "firebase-recipient-other";

    private static UsernamePasswordAuthenticationToken asSender(String firebaseUid) {
        return new UsernamePasswordAuthenticationToken(
                firebaseUid, null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
    }

    @BeforeEach
    void cleanDb() {
        recipientRepository.deleteAll();
        userRepository.deleteAll();
        seedUser(SENDER_UID, "+33600000021");
        seedUser(OTHER_UID, "+33600000022");
    }

    private void seedUser(String firebaseUid, String phone) {
        var user = new UserEntity();
        user.setFirebaseUid(firebaseUid);
        user.setPhoneNumber(phone);
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.PENDING);
        user.setRoles(new java.util.HashSet<>(List.of(Role.SENDER)));
        userRepository.save(user);
    }

    private String validCreateBody() throws Exception {
        return objectMapper.writeValueAsString(new java.util.HashMap<>() {{
            put("fullName", "Mamadou Diallo");
            put("phoneE164", "+221701234567");
            put("city", "Dakar");
            put("country", "SN");
        }});
    }

    @Test
    void list_empty_returnsEmptyArray() throws Exception {
        mockMvc.perform(get("/addressbook/recipients")
                .with(authentication(asSender(SENDER_UID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void create_validRequest_returns201() throws Exception {
        mockMvc.perform(post("/addressbook/recipients")
                .with(authentication(asSender(SENDER_UID)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validCreateBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fullName").value("Mamadou Diallo"))
                .andExpect(jsonPath("$.country").value("SN"))
                .andExpect(jsonPath("$.id").exists());
    }

    @Test
    void create_invalidPhone_returns422() throws Exception {
        String body = objectMapper.writeValueAsString(new java.util.HashMap<>() {{
            put("fullName", "Test");
            put("phoneE164", "0601020304"); // not E.164
            put("city", "Dakar");
            put("country", "SN");
        }});

        mockMvc.perform(post("/addressbook/recipients")
                .with(authentication(asSender(SENDER_UID)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.violations.phoneE164").exists());
    }

    @Test
    void create_invalidCountry_returns422() throws Exception {
        String body = objectMapper.writeValueAsString(new java.util.HashMap<>() {{
            put("fullName", "Test");
            put("phoneE164", "+221701234567");
            put("city", "Paris");
            put("country", "FR"); // not allowed (only SN, CI, ML, CM)
        }});

        mockMvc.perform(post("/addressbook/recipients")
                .with(authentication(asSender(SENDER_UID)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.violations.country").exists());
    }

    @Test
    void createThenList_returnsRecipient() throws Exception {
        mockMvc.perform(post("/addressbook/recipients")
                .with(authentication(asSender(SENDER_UID)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validCreateBody()))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/addressbook/recipients")
                .with(authentication(asSender(SENDER_UID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].fullName").value("Mamadou Diallo"));
    }

    @Test
    void delete_existing_returns204ThenEmpty() throws Exception {
        String created = mockMvc.perform(post("/addressbook/recipients")
                .with(authentication(asSender(SENDER_UID)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validCreateBody()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String id = objectMapper.readTree(created).get("id").asText();

        mockMvc.perform(delete("/addressbook/recipients/" + id)
                .with(authentication(asSender(SENDER_UID))))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/addressbook/recipients")
                .with(authentication(asSender(SENDER_UID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void delete_otherUserRecipient_returns404() throws Exception {
        String created = mockMvc.perform(post("/addressbook/recipients")
                .with(authentication(asSender(SENDER_UID)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validCreateBody()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String id = objectMapper.readTree(created).get("id").asText();

        mockMvc.perform(delete("/addressbook/recipients/" + id)
                .with(authentication(asSender(OTHER_UID))))
                .andExpect(status().isNotFound());
    }

    @Test
    void update_existingRecipient_returnsUpdated() throws Exception {
        String created = mockMvc.perform(post("/addressbook/recipients")
                .with(authentication(asSender(SENDER_UID)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validCreateBody()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String id = objectMapper.readTree(created).get("id").asText();

        String updateBody = objectMapper.writeValueAsString(new java.util.HashMap<>() {{
            put("fullName", "Fatou Diop");
            put("phoneE164", "+2250101234567");
            put("city", "Abidjan");
            put("country", "CI");
        }});

        mockMvc.perform(put("/addressbook/recipients/" + id)
                .with(authentication(asSender(SENDER_UID)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Fatou Diop"))
                .andExpect(jsonPath("$.country").value("CI"));
    }

    @Test
    void unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/addressbook/recipients"))
                .andExpect(status().isUnauthorized());
    }
}
