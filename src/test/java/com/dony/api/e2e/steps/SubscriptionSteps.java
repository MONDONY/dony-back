package com.dony.api.e2e.steps;

import io.cucumber.java.fr.Quand;

import java.util.Map;

/**
 * Steps for traveler subscriptions (a sender follows a traveler's announcements).
 */
public class SubscriptionSteps extends AbstractSteps {

    @Quand("je m'abonne au voyageur {string}")
    public void whenSubscribe(String travelerAlias) {
        store(asCurrentUser().post("/travelers/{id}/subscribe", ctx.getId(travelerAlias)));
    }

    @Quand("je me désabonne du voyageur {string}")
    public void whenUnsubscribe(String travelerAlias) {
        store(asCurrentUser().delete("/travelers/{id}/subscribe", ctx.getId(travelerAlias)));
    }

    @Quand("je consulte mon abonnement au voyageur {string}")
    public void whenGetSubscription(String travelerAlias) {
        store(asCurrentUser().get("/travelers/{id}/subscription", ctx.getId(travelerAlias)));
    }

    @Quand("je désactive les notifications push de l'abonnement au voyageur {string}")
    public void whenDisablePush(String travelerAlias) {
        store(asCurrentUser().body(Map.of("enabled", false))
                .put("/travelers/{id}/subscribe/push", ctx.getId(travelerAlias)));
    }

    @Quand("je consulte mes abonnements")
    public void whenGetMySubscriptions() {
        store(asCurrentUser().get("/me/subscriptions"));
    }

    @Quand("je consulte mes abonnés")
    public void whenGetMySubscribers() {
        store(asCurrentUser().get("/me/subscribers"));
    }

    @Quand("je marque comme vu l'abonnement au voyageur {string}")
    public void whenMarkSeen(String travelerAlias) {
        store(asCurrentUser().post("/me/subscriptions/{id}/mark-seen", ctx.getId(travelerAlias)));
    }
}
