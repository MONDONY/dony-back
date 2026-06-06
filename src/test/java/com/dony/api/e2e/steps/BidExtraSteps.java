package com.dony.api.e2e.steps;

import io.cucumber.java.fr.Quand;

import java.util.HashMap;
import java.util.Map;

/**
 * Steps for the secondary bid endpoints: quote, detail, presence confirmation,
 * parcel refusal and hide/dismiss.
 */
public class BidExtraSteps extends AbstractSteps {

    @Quand("je demande un devis pour {decimal} kg sur l'annonce {string}")
    public void whenQuote(Double kg, String announcementAlias) {
        Map<String, Object> body = new HashMap<>();
        body.put("announcementId", ctx.getId(announcementAlias).toString());
        body.put("weightKg", kg);
        store(asCurrentUser().body(body).post("/bids/quote"));
    }

    @Quand("je consulte le détail de l'offre {string}")
    public void whenGetBidDetail(String bidAlias) {
        store(asCurrentUser().get("/bids/{id}", ctx.getId(bidAlias)));
    }

    @Quand("le voyageur confirme sa présence pour l'offre {string}")
    public void whenConfirmPresence(String bidAlias) {
        store(asCurrentUser().put("/bids/{id}/confirm-presence", ctx.getId(bidAlias)));
    }

    @Quand("le voyageur refuse le colis de l'offre {string}")
    public void whenRefuseParcel(String bidAlias) {
        store(asCurrentUser().body(Map.of("reason", "Colis non conforme à la description"))
                .post("/bids/{id}/refuse-parcel", ctx.getId(bidAlias)));
    }

    @Quand("je masque l'offre {string}")
    public void whenHideBid(String bidAlias) {
        store(asCurrentUser().delete("/bids/{id}/me", ctx.getId(bidAlias)));
    }

    @Quand("le voyageur écarte l'offre {string}")
    public void whenDismissBid(String bidAlias) {
        store(asCurrentUser().delete("/bids/{id}/traveler", ctx.getId(bidAlias)));
    }
}
