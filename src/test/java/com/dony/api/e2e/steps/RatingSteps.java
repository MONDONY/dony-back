package com.dony.api.e2e.steps;

import io.cucumber.java.fr.Quand;

import java.util.HashMap;
import java.util.Map;

/**
 * Steps for the ratings subsystem (sender → traveler, traveler → sender, public summaries).
 * A rating requires a COMPLETED bid — see {@code TestDataSteps#givenBidCompleted}.
 */
public class RatingSteps extends AbstractSteps {

    @Quand("j'évalue le voyageur sur l'offre {string} avec {int} étoiles")
    public void whenRateTraveler(String bidAlias, int stars) {
        Map<String, Object> body = new HashMap<>();
        body.put("bidId", ctx.getId(bidAlias).toString());
        body.put("stars", stars);
        body.put("comment", "Très bon transporteur, ponctuel");
        store(asCurrentUser().body(body).post("/ratings"));
    }

    @Quand("j'évalue l'expéditeur sur l'offre {string} avec {int} étoiles")
    public void whenRateSender(String bidAlias, int stars) {
        Map<String, Object> body = new HashMap<>();
        body.put("bidId", ctx.getId(bidAlias).toString());
        body.put("stars", stars);
        body.put("comment", "Colis bien préparé");
        store(asCurrentUser().body(body).post("/ratings/traveler-to-sender"));
    }

    @Quand("je consulte les évaluations publiques du compte {string}")
    public void whenGetUserRatings(String alias) {
        store(asPublic().get("/ratings/user/{id}", ctx.getId(alias)));
    }

    @Quand("je consulte mes évaluations reçues")
    public void whenGetReceivedRatings() {
        store(asCurrentUser().get("/ratings/me/received"));
    }

    @Quand("je consulte mon évaluation en attente")
    public void whenGetPendingRating() {
        store(asCurrentUser().get("/ratings/pending"));
    }

    @Quand("le destinataire évalue avec {int} étoiles via le lien de suivi")
    public void whenRecipientRates(int stars) {
        Map<String, Object> body = new HashMap<>();
        body.put("trackingToken", ctx.getString("tracking-token"));
        body.put("stars", stars);
        body.put("comment", "Colis bien reçu, merci");
        store(asPublic().body(body).post("/ratings/recipient"));
    }
}
