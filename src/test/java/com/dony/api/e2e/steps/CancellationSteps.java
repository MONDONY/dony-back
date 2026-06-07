package com.dony.api.e2e.steps;

import io.cucumber.java.fr.Alors;
import io.cucumber.java.fr.Etantdonné;
import io.cucumber.java.fr.Quand;
import org.assertj.core.api.Assertions;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public class CancellationSteps extends AbstractSteps {

    // ── When ──────────────────────────────────────────────────────────────────

    @Quand("j'annule l'annonce {string} avec la raison {string}")
    public void whenCancelTrip(String announcementAlias, String reason) {
        store(asCurrentUser().body(Map.of(
                "announcementId", ctx.getId(announcementAlias).toString(),
                "reason", reason
        )).post("/cancellations"));
    }

    @Quand("je consulte les suggestions de rematch de l'annulation sauvegardée sous {string}")
    public void whenGetRematchSuggestions(String cancellationAlias) {
        store(asCurrentUser().get("/cancellations/{id}/rematch-suggestions",
                ctx.getId(cancellationAlias)));
    }

    @Quand("le voyageur signale un no-show pour l'offre {string}")
    public void whenReportNoShow(String bidAlias) {
        store(asCurrentUser().post("/cancellations/bids/{id}/report-noshow", ctx.getId(bidAlias)));
    }

    @Quand("l'administrateur confirme le no-show pour l'offre {string}")
    public void whenConfirmNoShow(String bidAlias) {
        store(asCurrentUser().post("/cancellations/bids/{id}/confirm-noshow", ctx.getId(bidAlias)));
    }

    @Quand("l'expéditeur conteste le no-show pour l'offre {string}")
    public void whenContestNoShow(String bidAlias) {
        store(asCurrentUser().post("/cancellations/bids/{id}/contest-noshow", ctx.getId(bidAlias)));
    }

    @Quand("je consulte mes litiges")
    public void whenGetMyDisputes() {
        store(asCurrentUser().get("/disputes/me"));
    }

    // ── Then ──────────────────────────────────────────────────────────────────

    @Alors("la réponse indique que {int} offre(s) ont été annulée(s)")
    public void thenAffectedCount(int count) {
        Integer actual = lastResponse().jsonPath().getInt("affectedBidsCount");
        Assertions.assertThat(actual).isEqualTo(count);
    }

    @Alors("l'identifiant de l'annulation est sauvegardé sous {string}")
    public void thenSaveCancellationId(String alias) {
        // CancellationResponse has announcementId field
        String announcementId = lastResponse().jsonPath().getString("announcementId");
        // Also try to get cancellation id if available
        String id = lastResponse().jsonPath().getString("id");
        if (id != null) {
            ctx.saveId(alias, UUID.fromString(id));
        } else {
            ctx.saveId(alias, UUID.fromString(announcementId));
        }
    }

    @Alors("les suggestions de rematch sont disponibles")
    public void thenRematchSuggestionsAvailable() {
        List<?> suggestions = lastResponse().jsonPath().getList("$");
        Assertions.assertThat(suggestions).isNotNull();
    }
}
