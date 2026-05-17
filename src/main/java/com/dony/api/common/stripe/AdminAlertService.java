package com.dony.api.common.stripe;

import io.sentry.Sentry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class AdminAlertService {
    private static final Logger log = LoggerFactory.getLogger(AdminAlertService.class);

    public void raise(String code, String detail, Map<String, Object> context) {
        log.error("[ADMIN ALERT] {} — {} | context={}", code, detail, context);
        Sentry.captureMessage("[ADMIN ALERT] " + code + " — " + detail);
    }
}
