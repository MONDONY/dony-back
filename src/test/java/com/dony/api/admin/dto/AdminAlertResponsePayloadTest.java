package com.dony.api.admin.dto;

import com.dony.api.admin.AdminAlertEntity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdminAlertResponsePayloadTest {

    @Test
    void from_parsesJsonPayloadIntoObject() {
        AdminAlertEntity e = new AdminAlertEntity();
        e.setType("ESCROW_J48_TIMEOUT");
        e.setPayload("{\"paymentId\":\"p-1\",\"hours\":48}");

        AdminAlertResponse resp = AdminAlertResponse.from(e);

        assertThat(resp.payload()).containsEntry("paymentId", "p-1");
        assertThat(resp.payload()).containsEntry("hours", 48);
    }

    @Test
    void from_nullOrMalformedPayload_neverThrows() {
        AdminAlertEntity empty = new AdminAlertEntity();
        assertThat(AdminAlertResponse.from(empty).payload()).isEmpty();

        AdminAlertEntity broken = new AdminAlertEntity();
        broken.setPayload("not-json{");
        assertThat(AdminAlertResponse.from(broken).payload()).containsKey("raw");
    }
}
