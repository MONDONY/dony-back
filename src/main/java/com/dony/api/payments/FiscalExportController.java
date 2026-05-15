package com.dony.api.payments;

import com.dony.api.auth.Role;
import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.DonyBusinessException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Year;
import java.util.List;

@RestController
@RequestMapping("/travelers/me/fiscal-export")
public class FiscalExportController {

    private static final List<String> VALID_FORMATS = List.of("pdf", "csv");
    private static final List<String> VALID_TYPES = List.of("summary", "transactions", "dac7");

    private final FiscalExportService exportService;
    private final UserRepository userRepository;

    public FiscalExportController(FiscalExportService exportService, UserRepository userRepository) {
        this.exportService = exportService;
        this.userRepository = userRepository;
    }

    @GetMapping
    public ResponseEntity<byte[]> downloadExport(
            @RequestParam int year,
            @RequestParam(defaultValue = "csv") String format,
            @RequestParam(defaultValue = "transactions") String type) {

        int currentYear = Year.now().getValue();
        if (year < 2020 || year > currentYear) {
            throw new DonyBusinessException(HttpStatus.BAD_REQUEST, "invalid-year",
                    "Invalid Year", "Année invalide. Plage autorisée: 2020–" + currentYear);
        }
        if (!VALID_FORMATS.contains(format)) {
            throw new DonyBusinessException(HttpStatus.BAD_REQUEST, "invalid-format",
                    "Invalid Format", "Format invalide. Valeurs acceptées: pdf, csv.");
        }
        if (!VALID_TYPES.contains(type)) {
            throw new DonyBusinessException(HttpStatus.BAD_REQUEST, "invalid-type",
                    "Invalid Type", "Type invalide. Valeurs acceptées: summary, transactions, dac7.");
        }

        UserEntity traveler = requireProTraveler();

        byte[] content;
        MediaType mediaType;
        String extension;

        if ("pdf".equals(format)) {
            content = exportService.generateHtml(traveler, year, type);
            mediaType = MediaType.TEXT_HTML;
            extension = "html";
        } else {
            content = exportService.generateCsv(traveler, year, type);
            mediaType = new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8);
            extension = "csv";
        }

        String filename = "dony-export-" + year + "-" + type + "." + extension;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(mediaType);
        headers.setContentDisposition(
                ContentDisposition.attachment().filename(filename).build());
        headers.setContentLength(content.length);

        return ResponseEntity.ok().headers(headers).body(content);
    }

    private UserEntity requireProTraveler() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new DonyBusinessException(
                    HttpStatus.UNAUTHORIZED, "unauthenticated", "Unauthenticated", "Authentification requise");
        }
        UserEntity user = userRepository.findByFirebaseUid(auth.getName())
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "user-not-found", "User Not Found", "Utilisateur introuvable"));
        if (!user.getRoles().contains(Role.TRAVELER) || !user.isProAccount()) {
            throw new DonyBusinessException(
                    HttpStatus.FORBIDDEN, "pro-required",
                    "PRO account required", "Export fiscal réservé aux voyageurs PRO.");
        }
        return user;
    }
}
