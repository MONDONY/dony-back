package com.dony.api.e2e.steps;

import io.cucumber.java.fr.Quand;

/**
 * Steps for PRO-traveler features: analytics, stats, calendar and fiscal export.
 * The PRO flag is provisioned via {@code TestDataSteps#givenProAccount}.
 */
public class ProSteps extends AbstractSteps {

    @Quand("je consulte mes analytics avec la période {string}")
    public void whenGetAnalytics(String period) {
        store(asCurrentUser().queryParam("period", period).get("/travelers/me/analytics"));
    }

    @Quand("je consulte mes statistiques de voyageur")
    public void whenGetStats() {
        store(asCurrentUser().get("/travelers/me/stats"));
    }

    @Quand("je consulte mon calendrier de voyageur")
    public void whenGetCalendar() {
        store(asCurrentUser().get("/travelers/me/calendar"));
    }

    @Quand("j'exporte mes données fiscales pour l'année {int} au format {string}")
    public void whenFiscalExport(int year, String format) {
        store(asCurrentUser()
                .queryParam("year", year)
                .queryParam("format", format)
                .queryParam("type", "transactions")
                .get("/travelers/me/fiscal-export"));
    }

    @Quand("j'exporte mes données fiscales pour l'année {int} au format {string} et le type {string}")
    public void whenFiscalExportTyped(int year, String format, String type) {
        store(asCurrentUser()
                .queryParam("year", year)
                .queryParam("format", format)
                .queryParam("type", type)
                .get("/travelers/me/fiscal-export"));
    }
}
