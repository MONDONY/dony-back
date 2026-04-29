package com.dony.api.messaging;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.AuditService;
import com.dony.api.matching.AnnouncementRepository;
import com.dony.api.matching.BidRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConversationServiceTest {

    @Mock ConversationRepository conversationRepository;
    @Mock FirestoreService firestoreService;
    @Mock UserRepository userRepository;
    @Mock AuditService auditService;
    @Mock BidRepository bidRepository;
    @Mock AnnouncementRepository announcementRepository;

    ConversationService service;

    UUID bidId      = UUID.randomUUID();
    UUID senderId   = UUID.randomUUID();
    UUID travelerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ConversationService(conversationRepository, firestoreService, userRepository, auditService,
                bidRepository, announcementRepository);

        UserEntity sender   = mockUser(senderId,   "Alice", "Martin", "uid-sender");
        UserEntity traveler = mockUser(travelerId, "Bob",   "Dupont", "uid-traveler");
        lenient().when(userRepository.findById(senderId)).thenReturn(Optional.of(sender));
        lenient().when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));
    }

    @Test
    void createConversation_persistsEntityAndCallsFirestore() {
        when(conversationRepository.findByBidId(bidId)).thenReturn(Optional.empty());
        ConversationEntity saved = new ConversationEntity(bidId, senderId, travelerId, "conv_" + bidId);
        when(conversationRepository.save(any())).thenReturn(saved);

        ConversationEntity result = service.createConversationForBid(bidId, senderId, travelerId);

        assertThat(result.getBidId()).isEqualTo(bidId);
        verify(firestoreService).createConversation(eq("conv_" + bidId), anyMap());
        verify(firestoreService).addSystemMessage(eq("conv_" + bidId), anyString());
        verify(auditService).log(eq("conversation"), any(), eq("CONVERSATION_CREATED"), eq(senderId), anyMap());
    }

    @Test
    void createConversation_isIdempotent_whenAlreadyExists() {
        ConversationEntity existing = new ConversationEntity(bidId, senderId, travelerId, "conv_" + bidId);
        when(conversationRepository.findByBidId(bidId)).thenReturn(Optional.of(existing));

        service.createConversationForBid(bidId, senderId, travelerId);

        verifyNoInteractions(firestoreService);
        verify(conversationRepository, never()).save(any());
    }

    private UserEntity mockUser(UUID id, String first, String last, String uid) {
        UserEntity u = mock(UserEntity.class);
        lenient().when(u.getId()).thenReturn(id);
        lenient().when(u.getFirstName()).thenReturn(first);
        lenient().when(u.getLastName()).thenReturn(last);
        lenient().when(u.getFirebaseUid()).thenReturn(uid);
        return u;
    }
}
