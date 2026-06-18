package com.dony.api.common;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class StorageControllerIT {

    @Autowired private MockMvc mockMvc;
    @MockBean private StorageService storageService;
    @MockBean private UserRepository userRepository;

    private static UsernamePasswordAuthenticationToken authAs(String uid) {
        return new UsernamePasswordAuthenticationToken(
            uid, null, List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
    }

    private static void setId(UserEntity user, UUID id) {
        try {
            var f = com.dony.api.common.BaseEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(user, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private MockMultipartFile jpeg() {
        // En-tête JPEG (FF D8 FF) — StorageService est mocké, le contenu importe peu.
        return new MockMultipartFile("file", "photo.jpg", "image/jpeg",
            new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00});
    }

    @Test
    void uploadPackageRequest_uidWithSpecialChars_resolvesUserAndReturnsKey() throws Exception {
        // Régression : un uid contenant un caractère hors [A-Za-z0-9_-] (ex. dev/phone)
        // ne doit PLUS être rejeté par invalid-uid — le prefix utilise user.getId().
        final String uid = "dev+221:7712";
        UUID userId = UUID.randomUUID();
        UserEntity user = new UserEntity();
        setId(user, userId);
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(user));
        when(storageService.uploadFile(any(), startsWith("package_requests/" + userId + "/")))
            .thenReturn("package_requests/" + userId + "/1.jpg");
        when(storageService.generatePresignedUrl(eq("package_requests/" + userId + "/1.jpg"),
            any(Duration.class))).thenReturn("https://signed/1");

        mockMvc.perform(multipart("/storage/upload/package-request")
                .file(jpeg())
                .with(authentication(authAs(uid))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.key").value("package_requests/" + userId + "/1.jpg"))
            .andExpect(jsonPath("$.url").value("https://signed/1"));
    }

    @Test
    void uploadPackageRequest_unknownUser_returns401() throws Exception {
        when(userRepository.findByFirebaseUid("ghost")).thenReturn(Optional.empty());

        mockMvc.perform(multipart("/storage/upload/package-request")
                .file(jpeg())
                .with(authentication(authAs("ghost"))))
            .andExpect(status().isUnauthorized());
    }
}
