package com.dony.api.auth;

import com.dony.api.auth.dto.BlockedUserDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class BlockControllerTest {

    @Autowired MockMvc mvc;
    @MockBean BlockService blockService;
    @MockBean AuthService authService;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID OTHER_ID = UUID.randomUUID();
    private static final String FIREBASE_UID = "uid-blocks-test";

    private UsernamePasswordAuthenticationToken auth() {
        return new UsernamePasswordAuthenticationToken(
                FIREBASE_UID, null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
    }

    @Test
    void POST_block_retourne204() throws Exception {
        when(authService.requireUserId()).thenReturn(USER_ID);
        doNothing().when(blockService).block(USER_ID, OTHER_ID);

        mvc.perform(post("/users/me/blocks")
                        .with(authentication(auth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"blockedUserId\":\"" + OTHER_ID + "\"}"))
                .andExpect(status().isNoContent());

        verify(blockService).block(USER_ID, OTHER_ID);
    }

    @Test
    void GET_blocks_retourne200() throws Exception {
        when(authService.requireUserId()).thenReturn(USER_ID);
        when(blockService.listBlocked(USER_ID)).thenReturn(List.of(
                new BlockedUserDto(OTHER_ID, "Mamadou D.", OffsetDateTime.now())
        ));

        mvc.perform(get("/users/me/blocks")
                        .with(authentication(auth()))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userId").value(OTHER_ID.toString()))
                .andExpect(jsonPath("$[0].displayName").value("Mamadou D."));
    }

    @Test
    void DELETE_block_retourne204() throws Exception {
        when(authService.requireUserId()).thenReturn(USER_ID);
        doNothing().when(blockService).unblock(USER_ID, OTHER_ID);

        mvc.perform(delete("/users/me/blocks/" + OTHER_ID)
                        .with(authentication(auth())))
                .andExpect(status().isNoContent());

        verify(blockService).unblock(USER_ID, OTHER_ID);
    }

    @Test
    void POST_block_sans_auth_retourne401() throws Exception {
        mvc.perform(post("/users/me/blocks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"blockedUserId\":\"" + OTHER_ID + "\"}"))
                .andExpect(status().isUnauthorized());
    }
}
