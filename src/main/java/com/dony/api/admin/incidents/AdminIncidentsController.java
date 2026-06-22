package com.dony.api.admin.incidents;

import com.dony.api.admin.account.AdminPrincipal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@PreAuthorize("hasRole('ADMIN')")
public class AdminIncidentsController {

    private final AdminIncidentsService service;

    public AdminIncidentsController(AdminIncidentsService service) {
        this.service = service;
    }

    // ---- Disputes -----------------------------------------------------------

    @GetMapping("/admin/disputes")
    @PreAuthorize("hasAuthority('USER_VIEW')")
    public Page<AdminDisputeSummary> listDisputes(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.listDisputes(status, PageRequest.of(page, size));
    }

    @GetMapping("/admin/disputes/{id}")
    @PreAuthorize("hasAuthority('USER_VIEW')")
    public AdminDisputeDetailResponse getDispute(@PathVariable UUID id) {
        return service.getDispute(id);
    }

    @PostMapping("/admin/disputes/{id}/resolve")
    @PreAuthorize("hasAuthority('USER_BAN')")
    public AdminDisputeDetailResponse resolve(
            @PathVariable UUID id,
            @RequestBody ResolveRequest body,
            Authentication auth) {
        UUID actorId = ((AdminPrincipal) auth.getPrincipal()).adminId();
        return service.resolve(id, body.resolution(), body.note(), actorId);
    }

    @PostMapping("/admin/disputes/{id}/guarantee-fund")
    @PreAuthorize("hasAuthority('USER_BAN')")
    public AdminDisputeDetailResponse payGuaranteeFund(
            @PathVariable UUID id,
            @RequestBody GuaranteeFundRequest body,
            Authentication auth) {
        UUID actorId = ((AdminPrincipal) auth.getPrincipal()).adminId();
        return service.payGuaranteeFund(id, body.amountCents(), body.beneficiaryUserId(), body.reason(), actorId);
    }

    // ---- Cancellations ------------------------------------------------------

    @GetMapping("/admin/cancellations")
    @PreAuthorize("hasAuthority('USER_VIEW')")
    public Page<AdminCancellationResponse> listCancellations(
            @RequestParam(required = false) String noShowStatus,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.listCancellations(noShowStatus, PageRequest.of(page, size));
    }

    // ---- Records ------------------------------------------------------------

    public record ResolveRequest(String resolution, String note) {}
    public record GuaranteeFundRequest(int amountCents, UUID beneficiaryUserId, String reason) {}
}
