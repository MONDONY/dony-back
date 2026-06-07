package com.dony.api.e2e.steps;

import io.cucumber.java.fr.Quand;

/**
 * Steps for the cash-commission method (Stripe SetupIntent flow, stubbed in e2e).
 */
public class CashSteps extends AbstractSteps {

    @Quand("je configure ma méthode de paiement de commission")
    public void whenSetupCommissionMethod() {
        store(asCurrentUser().post("/traveler/commission-method/setup"));
    }

    @Quand("je consulte ma méthode de paiement de commission")
    public void whenGetCommissionMethod() {
        store(asCurrentUser().get("/traveler/commission-method"));
    }

    @Quand("je supprime ma méthode de paiement de commission")
    public void whenDeleteCommissionMethod() {
        store(asCurrentUser().delete("/traveler/commission-method"));
    }
}
