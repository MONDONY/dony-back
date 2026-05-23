package com.dony.api.auth;

import com.dony.api.auth.dto.BlockedUserDto;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.matching.BidRepository;
import com.dony.api.matching.BidStatus;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class BlockService {

    private static final List<BidStatus> ACTIVE_STATUSES = List.of(
            BidStatus.PENDING, BidStatus.PAYMENT_ESCROWED, BidStatus.ACCEPTED,
            BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT);

    private final UserBlockJpaRepository blockRepo;
    private final UserRepository userRepository;
    private final BidRepository bidRepository;

    public BlockService(UserBlockJpaRepository blockRepo, UserRepository userRepository,
                        BidRepository bidRepository) {
        this.blockRepo = blockRepo;
        this.userRepository = userRepository;
        this.bidRepository = bidRepository;
    }

    @Transactional
    public void block(UUID blockerId, UUID blockedId) {
        if (blockerId.equals(blockedId)) {
            throw new DonyBusinessException(HttpStatus.BAD_REQUEST, "invalid-block",
                    "Invalid Block", "Action invalide");
        }
        if (blockRepo.existsByBlockerIdAndBlockedId(blockerId, blockedId)) {
            return; // idempotent
        }
        if (bidRepository.hasActiveTransactionBetween(blockerId, blockedId, ACTIVE_STATUSES)) {
            throw new DonyBusinessException(HttpStatus.CONFLICT, "active-transaction",
                    "Active Transaction",
                    "Termine d'abord la transaction en cours avant de bloquer cet utilisateur");
        }
        UserBlockEntity entity = new UserBlockEntity();
        entity.setBlockerId(blockerId);
        entity.setBlockedId(blockedId);
        blockRepo.save(entity);
    }

    @Transactional
    public void unblock(UUID blockerId, UUID blockedId) {
        blockRepo.deleteByBlockerIdAndBlockedId(blockerId, blockedId);
    }

    @Transactional(readOnly = true)
    public List<BlockedUserDto> listBlocked(UUID blockerId) {
        return blockRepo.findByBlockerIdOrderByCreatedAtDesc(blockerId).stream()
                .map(b -> {
                    UserEntity u = userRepository.findById(b.getBlockedId()).orElse(null);
                    String name = (u != null)
                            ? u.getFirstName() + " " + (u.getLastName() != null && !u.getLastName().isEmpty()
                                ? u.getLastName().charAt(0) + "." : "")
                            : "Utilisateur";
                    return new BlockedUserDto(b.getBlockedId(), name.trim(), b.getCreatedAt());
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public boolean isBlockedEitherWay(UUID a, UUID b) {
        return blockRepo.existsBetween(a, b);
    }
}
