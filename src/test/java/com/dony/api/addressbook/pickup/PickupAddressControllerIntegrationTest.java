package com.dony.api.addressbook.pickup;

import com.dony.api.auth.Role;
import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
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
import java.util.Set;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class PickupAddressControllerIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired PickupAddressRepository pickupAddressRepository;
    @Autowired UserRepository userRepository;

    private static final UUID SENDER_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID OTHER_ID  = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");

    private static UsernamePasswordAuthenticationToken asSender(UUID userId) {
        return new UsernamePasswordAuthenticationToken(
                userId.toString(), null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
    }

    @BeforeEach
    void cleanDb() {
        pickupAddressRepository.deleteAll();
        userRepository.deleteAll();
    }

    private String createRequest(String label) throws Exception {
        return objectMapper.writeValueAsString(new java.util.HashMap<>() {{
            put("label", label);
            put("street", "10 rue de la Paix");
            put("postalCode", "75001");
            put("city", "Paris");
            put("country", "FR");
            put("isDefault", false);
        }});
    }

    @Test
    void list_emptyDb_returnsEmptyArray() throws Exception {
        mockMvc.perform(get("/addressbook/pickup-addresses")
                .with(authentication(asSender(SENDER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void create_validRequest_returns201WithBody() throws Exception {
        mockMvc.perform(post("/addressbook/pickup-addresses")
                .with(authentication(asSender(SENDER_ID)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createRequest("Maison")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.label").value("Maison"))
                .andExpect(jsonPath("$.city").value("Paris"))
                .andExpect(jsonPath("$.id").exists());
    }

    @Test
    void create_thenList_returnsCreatedAddress() throws Exception {
        mockMvc.perform(post("/addressbook/pickup-addresses")
                .with(authentication(asSender(SENDER_ID)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createRequest("Bureau")))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/addressbook/pickup-addresses")
                .with(authentication(asSender(SENDER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].label").value("Bureau"));
    }

    @Test
    void create_invalidRequest_missingLabel_returns422() throws Exception {
        String body = objectMapper.writeValueAsString(new java.util.HashMap<>() {{
            put("street", "5 avenue");
            put("postalCode", "75008");
            put("city", "Paris");
            put("country", "FR");
        }});

        mockMvc.perform(post("/addressbook/pickup-addresses")
                .with(authentication(asSender(SENDER_ID)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.violations.label").exists());
    }

    @Test
    void delete_existing_returns204ThenListEmpty() throws Exception {
        String createdResponse = mockMvc.perform(post("/addressbook/pickup-addresses")
                .with(authentication(asSender(SENDER_ID)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createRequest("Maison")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String id = objectMapper.readTree(createdResponse).get("id").asText();

        mockMvc.perform(delete("/addressbook/pickup-addresses/" + id)
                .with(authentication(asSender(SENDER_ID))))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/addressbook/pickup-addresses")
                .with(authentication(asSender(SENDER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void delete_otherUserAddress_returns404() throws Exception {
        // SENDER creates an address
        String createdResponse = mockMvc.perform(post("/addressbook/pickup-addresses")
                .with(authentication(asSender(SENDER_ID)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createRequest("Maison Sender")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String id = objectMapper.readTree(createdResponse).get("id").asText();

        // OTHER tries to delete it → 404 (ownership check)
        mockMvc.perform(delete("/addressbook/pickup-addresses/" + id)
                .with(authentication(asSender(OTHER_ID))))
                .andExpect(status().isNotFound());
    }

    @Test
    void unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/addressbook/pickup-addresses"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void setDefault_changesDefaultFlag() throws Exception {
        // create first address without default
        String r1 = mockMvc.perform(post("/addressbook/pickup-addresses")
                .with(authentication(asSender(SENDER_ID)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createRequest("Addr1")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String id1 = objectMapper.readTree(r1).get("id").asText();

        mockMvc.perform(patch("/addressbook/pickup-addresses/" + id1 + "/set-default")
                .with(authentication(asSender(SENDER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isDefault").value(true));
    }

    @Test
    void update_existingAddress_returnsUpdatedDto() throws Exception {
        String created = mockMvc.perform(post("/addressbook/pickup-addresses")
                .with(authentication(asSender(SENDER_ID)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createRequest("Original")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String id = objectMapper.readTree(created).get("id").asText();

        String updateBody = objectMapper.writeValueAsString(new java.util.HashMap<>() {{
            put("label", "Updated");
            put("street", "99 rue Rivoli");
            put("postalCode", "75001");
            put("city", "Paris");
            put("country", "FR");
            put("isDefault", false);
        }});

        mockMvc.perform(put("/addressbook/pickup-addresses/" + id)
                .with(authentication(asSender(SENDER_ID)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("Updated"))
                .andExpect(jsonPath("$.street").value("99 rue Rivoli"));
    }
}
