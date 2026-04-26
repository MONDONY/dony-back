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

    // ── Then ──────────────────────────────────────────────────────────────────

    @Alors("la réponse indique que {int} offre(s) ont été annulée(s)")
    public void thenAffectedCount(int count) {
        Integer actual = lastResponse().jsonPath().getInt("affectedCount");
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
