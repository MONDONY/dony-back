package com.yadony.api.smsotp;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "yadony.sms")
public class SmsOtpProperties {

    private String otpTemplate = "Ton code Yadony est : %s. Valable 10 minutes.";

    public String getOtpTemplate() { return otpTemplate; }
    public void setOtpTemplate(String otpTemplate) { this.otpTemplate = otpTemplate; }
}
