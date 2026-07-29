package com.yadony.api.export;

import com.yadony.api.addressbook.recipient.RecipientEntity;
import com.yadony.api.addressbook.recipient.RecipientRepository;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashSet;
import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class UserDataExportControllerIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired RecipientRepository recipientRepository;

    private static final String SENDER_UID = "firebase-export-sender";

    private static UsernamePasswordAuthenticationToken asSender(String firebaseUid) {
        return new UsernamePasswordAuthenticationToken(
                firebaseUid, null, List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
    }

    @BeforeEach
    void cleanDb() {
        recipientRepository.deleteAll();
        userRepository.deleteAll();

        var user = new UserEntity();
        user.setFirebaseUid(SENDER_UID);
        user.setFirstName("Fatou");
        user.setLastName("Diop");
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.PENDING);
        user.setRoles(new HashSet<>(List.of(Role.SENDER)));
        UserEntity saved = userRepository.save(user);

        RecipientEntity recipient = new RecipientEntity();
        recipient.setUserId(saved.getId());
        recipient.setFullName("Maman");
        recipient.setPhoneE164("+221771234567");
        recipient.setCity("Dakar");
        recipient.setCountry("SN");
        recipientRepository.save(recipient);
    }

    @Test
    void export_authenticated_returnsOwnProfileAndRecipients() throws Exception {
        mockMvc.perform(get("/users/me/export")
                .with(authentication(asSender(SENDER_UID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile.firstName").value("Fatou"))
                .andExpect(jsonPath("$.profile.lastName").value("Diop"))
                .andExpect(jsonPath("$.recipients.length()").value(1))
                .andExpect(jsonPath("$.recipients[0].fullName").value("Maman"))
                .andExpect(jsonPath("$.kyc").doesNotExist())
                .andExpect(jsonPath("$.generatedAt").exists());
    }

    @Test
    void export_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/users/me/export"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void export_neverIncludesOtherUsersRecipients() throws Exception {
        var other = new UserEntity();
        other.setFirebaseUid("firebase-export-other");
        other.setStatus(UserStatus.ACTIVE);
        other.setKycStatus(KycStatus.PENDING);
        other.setRoles(new HashSet<>(List.of(Role.SENDER)));
        UserEntity savedOther = userRepository.save(other);

        RecipientEntity otherRecipient = new RecipientEntity();
        otherRecipient.setUserId(savedOther.getId());
        otherRecipient.setFullName("Pas le mien");
        otherRecipient.setPhoneE164("+221779999999");
        otherRecipient.setCity("Abidjan");
        otherRecipient.setCountry("CI");
        recipientRepository.save(otherRecipient);

        mockMvc.perform(get("/users/me/export")
                .with(authentication(asSender(SENDER_UID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipients.length()").value(1))
                .andExpect(jsonPath("$.recipients[0].fullName").value("Maman"));
    }
}
