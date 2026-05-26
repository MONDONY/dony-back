package com.dony.api.addressbook.delivery;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class DeliveryAddressControllerIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired DeliveryAddressRepository deliveryAddressRepository;
    @Autowired UserRepository userRepository;

    private static final String SENDER_UID = "firebase-delivery-sender";
    private static final String OTHER_UID  = "firebase-delivery-other";

    private static UsernamePasswordAuthenticationToken asSender(String firebaseUid) {
        return new UsernamePasswordAuthenticationToken(
                firebaseUid, null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
    }

    @BeforeEach
    void cleanDb() {
        deliveryAddressRepository.deleteAll();
        userRepository.deleteAll();
        seedUser(SENDER_UID, "+33600000011");
        seedUser(OTHER_UID, "+33600000012");
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

    private String createRequest(String label) throws Exception {
        return objectMapper.writeValueAsString(new java.util.HashMap<>() {{
            put("label", label);
            put("city", "Dakar");
            put("country", "SN");
            put("isDefault", false);
        }});
    }

    @Test
    void list_emptyDb_returnsEmptyArray() throws Exception {
        mockMvc.perform(get("/addressbook/delivery-addresses")
                .with(authentication(asSender(SENDER_UID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void create_thenList_returnsCreatedAddress() throws Exception {
        mockMvc.perform(post("/addressbook/delivery-addresses")
                .with(authentication(asSender(SENDER_UID)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createRequest("Famille Dakar")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.label").value("Famille Dakar"))
                .andExpect(jsonPath("$.city").value("Dakar"))
                .andExpect(jsonPath("$.id").exists());

        mockMvc.perform(get("/addressbook/delivery-addresses")
                .with(authentication(asSender(SENDER_UID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].label").value("Famille Dakar"));
    }

    @Test
    void unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/addressbook/delivery-addresses"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void delete_otherUserAddress_returns404() throws Exception {
        String createdResponse = mockMvc.perform(post("/addressbook/delivery-addresses")
                .with(authentication(asSender(SENDER_UID)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createRequest("Famille Dakar")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String id = objectMapper.readTree(createdResponse).get("id").asText();

        mockMvc.perform(delete("/addressbook/delivery-addresses/" + id)
                .with(authentication(asSender(OTHER_UID))))
                .andExpect(status().isNotFound());
    }
}
