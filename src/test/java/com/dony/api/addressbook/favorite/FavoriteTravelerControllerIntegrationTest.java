package com.dony.api.addressbook.favorite;

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
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class FavoriteTravelerControllerIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired FavoriteTravelerRepository favoriteTravelerRepository;
    @Autowired UserRepository userRepository;

    private static final UUID SENDER_ID   = UUID.fromString("cccccccc-0000-0000-0000-000000000001");
    private static final UUID TRAVELER_ID = UUID.fromString("cccccccc-0000-0000-0000-000000000002");

    private static UsernamePasswordAuthenticationToken asSender(UUID userId) {
        return new UsernamePasswordAuthenticationToken(
                userId.toString(), null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
    }

    @BeforeEach
    void cleanDb() {
        favoriteTravelerRepository.deleteAll();
        userRepository.deleteAll();
    }

    private UserEntity createTraveler(UUID id) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("firebase-" + id);
        u.setFirstName("Ousmane");
        u.setLastName("Diallo");
        u.setPhoneNumber("+33600" + id.toString().substring(0, 5));
        u.setRoles(Set.of(Role.TRAVELER));
        return userRepository.save(u);
    }

    @Test
    void list_empty_returnsEmptyArray() throws Exception {
        mockMvc.perform(get("/addressbook/favorite-travelers")
                .with(authentication(asSender(SENDER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void add_validTraveler_returns201() throws Exception {
        UserEntity traveler = createTraveler(TRAVELER_ID);

        String body = objectMapper.writeValueAsString(Map.of("travelerId", traveler.getId().toString()));

        mockMvc.perform(post("/addressbook/favorite-travelers")
                .with(authentication(asSender(SENDER_ID)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.travelerId").value(traveler.getId().toString()))
                .andExpect(jsonPath("$.displayName").value("Ousmane D."))
                .andExpect(jsonPath("$.id").exists());
    }

    @Test
    void add_nonExistentTraveler_returns404() throws Exception {
        UUID nonExistent = UUID.randomUUID();
        String body = objectMapper.writeValueAsString(Map.of("travelerId", nonExistent.toString()));

        mockMvc.perform(post("/addressbook/favorite-travelers")
                .with(authentication(asSender(SENDER_ID)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isNotFound());
    }

    @Test
    void add_duplicate_returns409() throws Exception {
        UserEntity traveler = createTraveler(TRAVELER_ID);
        String body = objectMapper.writeValueAsString(Map.of("travelerId", traveler.getId().toString()));

        mockMvc.perform(post("/addressbook/favorite-travelers")
                .with(authentication(asSender(SENDER_ID)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/addressbook/favorite-travelers")
                .with(authentication(asSender(SENDER_ID)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void addThenList_returnsFavorite() throws Exception {
        UserEntity traveler = createTraveler(TRAVELER_ID);
        String body = objectMapper.writeValueAsString(Map.of("travelerId", traveler.getId().toString()));

        mockMvc.perform(post("/addressbook/favorite-travelers")
                .with(authentication(asSender(SENDER_ID)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/addressbook/favorite-travelers")
                .with(authentication(asSender(SENDER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].travelerId").value(traveler.getId().toString()));
    }

    @Test
    void delete_existing_returns204ThenEmpty() throws Exception {
        UserEntity traveler = createTraveler(TRAVELER_ID);
        String body = objectMapper.writeValueAsString(Map.of("travelerId", traveler.getId().toString()));

        mockMvc.perform(post("/addressbook/favorite-travelers")
                .with(authentication(asSender(SENDER_ID)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(delete("/addressbook/favorite-travelers/" + traveler.getId())
                .with(authentication(asSender(SENDER_ID))))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/addressbook/favorite-travelers")
                .with(authentication(asSender(SENDER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void delete_notFavorite_returns404() throws Exception {
        UserEntity traveler = createTraveler(TRAVELER_ID);

        mockMvc.perform(delete("/addressbook/favorite-travelers/" + traveler.getId())
                .with(authentication(asSender(SENDER_ID))))
                .andExpect(status().isNotFound());
    }

    @Test
    void unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/addressbook/favorite-travelers"))
                .andExpect(status().isUnauthorized());
    }
}
