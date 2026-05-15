package com.dony.api.payments;

import com.dony.api.auth.UserEntity;
import com.dony.api.matching.AnnouncementEntity;
import com.dony.api.matching.AnnouncementRepository;
import com.dony.api.matching.BidEntity;
import com.dony.api.matching.BidRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class FiscalExportService {

    private final PaymentRepository paymentRepository;
    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;

    public FiscalExportService(PaymentRepository paymentRepository,
                               BidRepository bidRepository,
                               AnnouncementRepository announcementRepository) {
        this.paymentRepository = paymentRepository;
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
    }

    public byte[] generateCsv(UserEntity traveler, int year, String type) {
        List<PaymentEntity> payments = fetchPayments(traveler, year);
        return switch (type) {
            case "summary" -> buildSummaryCsv(payments, traveler, year).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            case "dac7" -> buildDac7Csv(payments, traveler, year).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            default -> buildTransactionsCsv(payments).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        };
    }

    public byte[] generateHtml(UserEntity traveler, int year, String type) {
        List<PaymentEntity> payments = fetchPayments(traveler, year);
        String body = switch (type) {
            case "summary" -> buildSummaryHtml(payments, traveler, year);
            case "dac7" -> buildDac7Html(payments, traveler, year);
            default -> buildTransactionsHtml(payments, year);
        };
        return wrapHtml(body, year, type).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    // ---- Data fetching ----

    private List<PaymentEntity> fetchPayments(UserEntity traveler, int year) {
        LocalDateTime from = LocalDateTime.of(year, 1, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(year, 12, 31, 23, 59, 59);
        return paymentRepository.findReleasedByTravelerAndYear(traveler.getId(), from, to);
    }

    // ---- CSV builders ----

    private String buildSummaryCsv(List<PaymentEntity> payments, UserEntity traveler, int year) {
        var sb = new StringBuilder();
        sb.append("Export fiscal Dony — Résumé annuel\n");
        sb.append("Année,Nom,Revenu brut (€),Commissions (€),Revenu net (€)\n");
        BigDecimal gross = payments.stream().map(PaymentEntity::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal commission = payments.stream().map(PaymentEntity::getCommissionAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal net = gross.subtract(commission);
        String name = traveler.getFirstName() + " " + traveler.getLastName();
        sb.append(year).append(",").append(csv(name)).append(",")
                .append(eur(gross)).append(",").append(eur(commission)).append(",").append(eur(net));
        return sb.toString();
    }

    private String buildTransactionsCsv(List<PaymentEntity> payments) {
        var sb = new StringBuilder();
        sb.append("Date,Trajet,Montant brut (€),Commission (€),Montant net (€)\n");
        for (PaymentEntity p : payments) {
            String corridor = resolveCorridorForPayment(p);
            sb.append(p.getCreatedAt().toLocalDate()).append(",")
                    .append(csv(corridor)).append(",")
                    .append(eur(p.getAmount())).append(",")
                    .append(eur(p.getCommissionAmount())).append(",")
                    .append(eur(p.getAmount().subtract(p.getCommissionAmount()))).append("\n");
        }
        return sb.toString();
    }

    private String buildDac7Csv(List<PaymentEntity> payments, UserEntity traveler, int year) {
        var sb = new StringBuilder();
        sb.append("# Export DAC7 — Dony — ").append(year).append("\n");
        sb.append("Champ,Valeur\n");
        sb.append("Plateforme,Dony SAS\n");
        sb.append("Année,").append(year).append("\n");
        sb.append("Prénom,").append(csv(traveler.getFirstName())).append("\n");
        sb.append("Nom,").append(csv(traveler.getLastName())).append("\n");
        sb.append("Pays,").append(csv(traveler.getCountry())).append("\n");
        BigDecimal gross = payments.stream().map(PaymentEntity::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal commission = payments.stream().map(PaymentEntity::getCommissionAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        sb.append("Revenu brut total (€),").append(eur(gross)).append("\n");
        sb.append("Commissions retenues (€),").append(eur(commission)).append("\n");
        sb.append("Revenu net total (€),").append(eur(gross.subtract(commission))).append("\n");
        sb.append("Nombre de transactions,").append(payments.size()).append("\n");
        return sb.toString();
    }

    // ---- HTML builders ----

    private String buildSummaryHtml(List<PaymentEntity> payments, UserEntity traveler, int year) {
        BigDecimal gross = payments.stream().map(PaymentEntity::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal commission = payments.stream().map(PaymentEntity::getCommissionAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal net = gross.subtract(commission);
        return "<h2>Résumé annuel " + year + "</h2>" +
                "<p><strong>Nom :</strong> " + esc(traveler.getFirstName() + " " + traveler.getLastName()) + "</p>" +
                "<table><tr><th>Revenu brut</th><th>Commissions Dony</th><th>Revenu net</th></tr>" +
                "<tr><td>" + eur(gross) + " €</td><td>" + eur(commission) + " €</td><td>" + eur(net) + " €</td></tr>" +
                "</table>";
    }

    private String buildTransactionsHtml(List<PaymentEntity> payments, int year) {
        var sb = new StringBuilder();
        sb.append("<h2>Transactions ").append(year).append("</h2>");
        sb.append("<table><tr><th>Date</th><th>Trajet</th><th>Brut (€)</th><th>Commission (€)</th><th>Net (€)</th></tr>");
        for (PaymentEntity p : payments) {
            String corridor = resolveCorridorForPayment(p);
            sb.append("<tr><td>").append(p.getCreatedAt().toLocalDate())
                    .append("</td><td>").append(esc(corridor))
                    .append("</td><td>").append(eur(p.getAmount()))
                    .append("</td><td>").append(eur(p.getCommissionAmount()))
                    .append("</td><td>").append(eur(p.getAmount().subtract(p.getCommissionAmount())))
                    .append("</td></tr>");
        }
        sb.append("</table>");
        return sb.toString();
    }

    private String buildDac7Html(List<PaymentEntity> payments, UserEntity traveler, int year) {
        BigDecimal gross = payments.stream().map(PaymentEntity::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal commission = payments.stream().map(PaymentEntity::getCommissionAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return "<h2>Déclaration DAC7 — " + year + "</h2>" +
                "<p><strong>Plateforme :</strong> Dony SAS</p>" +
                "<p><strong>Prestataire :</strong> " + esc(traveler.getFirstName() + " " + traveler.getLastName()) + "</p>" +
                "<p><strong>Pays :</strong> " + esc(traveler.getCountry()) + "</p>" +
                "<table><tr><th>Revenu brut</th><th>Commissions</th><th>Revenu net</th><th>Transactions</th></tr>" +
                "<tr><td>" + eur(gross) + " €</td><td>" + eur(commission) + " €</td>" +
                "<td>" + eur(gross.subtract(commission)) + " €</td><td>" + payments.size() + "</td></tr>" +
                "</table>" +
                "<p style='margin-top:20px;font-size:11px;color:#666;'>Document généré automatiquement par Dony. " +
                "Ouvrez ce fichier dans un navigateur puis imprimez-le (Ctrl+P) pour obtenir un PDF.</p>";
    }

    private String wrapHtml(String body, int year, String type) {
        return "<!DOCTYPE html><html lang='fr'><head><meta charset='UTF-8'>" +
                "<title>Export fiscal Dony " + year + "</title>" +
                "<style>body{font-family:Arial,sans-serif;margin:40px;color:#333}" +
                "h1{color:#0B5FFF}h2{color:#333;border-bottom:1px solid #ddd;padding-bottom:8px}" +
                "table{border-collapse:collapse;width:100%}th,td{border:1px solid #ddd;padding:8px 12px;text-align:left}" +
                "th{background:#f5f5f5;font-weight:600}@media print{body{margin:20px}}</style>" +
                "</head><body><h1>Export fiscal Dony</h1>" + body + "</body></html>";
    }

    // ---- Helpers ----

    private String resolveCorridorForPayment(PaymentEntity payment) {
        if (payment.getBidId() == null) return "—";
        return bidRepository.findById(payment.getBidId())
                .map(bid -> announcementRepository.findById(bid.getAnnouncementId())
                        .map(a -> a.getDepartureCity() + " → " + a.getArrivalCity())
                        .orElse("—"))
                .orElse("—");
    }

    private String eur(BigDecimal value) {
        return value == null ? "0.00" : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private String csv(String value) {
        if (value == null) return "";
        return value.contains(",") || value.contains("\"") || value.contains("\n")
                ? "\"" + value.replace("\"", "\"\"") + "\""
                : value;
    }

    private String esc(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
