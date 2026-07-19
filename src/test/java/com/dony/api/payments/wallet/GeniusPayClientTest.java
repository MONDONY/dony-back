package com.dony.api.payments.wallet;

import com.dony.api.common.DonyBusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GeniusPayClientTest {

    @Mock private RestTemplate restTemplate;
    private GeniusPayClient client;

    @BeforeEach
    void setUp() {
        GeniusPayProperties props = new GeniusPayProperties(
                "pk_sandbox_test", "sk_sandbox_test",
                "https://pay.genius.ci/api/v1/merchant", "whsec_test");
        client = new GeniusPayClient(restTemplate, props);
    }

    @SuppressWarnings("unchecked")
    @Test
    void createPayment_success_returnsReferenceAndPaymentUrl() {
        Map<String, Object> body = Map.of(
                "success", true,
                "data", Map.of(
                        "id", 456,
                        "reference", "MTX-A1B2C3D4E5",
                        "amount", 6560,
                        "status", "pending",
                        "checkout_url", "https://wave.com/pay/xxx",
                        "gateway", "wave",
                        "environment", "sandbox"));
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(body));

        GeniusPayPaymentResult result = client.createPayment(6560L, "XOF", "wave", "+221771234567",
                "Recharge wallet dony", "https://api.dony.app/api/v1/payments/geniuspay/return?status=success",
                "https://api.dony.app/api/v1/payments/geniuspay/return?status=error");

        assertThat(result.reference()).isEqualTo("MTX-A1B2C3D4E5");
        assertThat(result.paymentUrl()).isEqualTo("https://wave.com/pay/xxx");
    }

    @SuppressWarnings("unchecked")
    @Test
    void createPayment_sendsDirectModeWithExplicitPaymentMethod() {
        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);
        Map<String, Object> body = Map.of("success", true, "data", Map.of(
                "reference", "MTX-X", "checkout_url", "https://wave.com/pay/x"));
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), captor.capture(), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(body));

        client.createPayment(6560L, "XOF", "wave", "+221771234567", "Recharge wallet dony",
                "https://api.dony.app/api/v1/payments/geniuspay/return?status=success",
                "https://api.dony.app/api/v1/payments/geniuspay/return?status=error");

        Map<String, Object> sentBody = (Map<String, Object>) captor.getValue().getBody();
        assertThat(sentBody).containsEntry("payment_method", "wave");
        assertThat(sentBody).containsEntry("amount", 6560L);
        assertThat(sentBody).containsEntry("currency", "XOF");
        assertThat(sentBody).containsEntry("success_url",
                "https://api.dony.app/api/v1/payments/geniuspay/return?status=success");
        assertThat(sentBody).containsEntry("error_url",
                "https://api.dony.app/api/v1/payments/geniuspay/return?status=error");
        assertThat(captor.getValue().getHeaders().getFirst("X-API-Key")).isEqualTo("pk_sandbox_test");
        assertThat(captor.getValue().getHeaders().getFirst("X-API-Secret")).isEqualTo("sk_sandbox_test");
    }

    @Test
    void createPayment_httpClientError_mapsToDonyBusinessException() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(HttpClientErrorException.create(
                        org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                        "Unprocessable", null, null, null));

        assertThatThrownBy(() -> client.createPayment(6560L, "XOF", "wave", "+221771234567", "desc",
                "https://api.dony.app/api/v1/payments/geniuspay/return?status=success",
                "https://api.dony.app/api/v1/payments/geniuspay/return?status=error"))
                .isInstanceOf(DonyBusinessException.class);
    }

    @Test
    void createPayment_timeout_mapsToDonyBusinessException() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new ResourceAccessException("timeout"));

        assertThatThrownBy(() -> client.createPayment(6560L, "XOF", "wave", "+221771234567", "desc",
                "https://api.dony.app/api/v1/payments/geniuspay/return?status=success",
                "https://api.dony.app/api/v1/payments/geniuspay/return?status=error"))
                .isInstanceOf(DonyBusinessException.class)
                .satisfies(e -> assertThat(((DonyBusinessException) e).getStatus())
                        .isEqualTo(org.springframework.http.HttpStatus.GATEWAY_TIMEOUT));
    }
}
