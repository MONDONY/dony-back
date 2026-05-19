package com.dony.api.emailotp;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "dony.email")
public class EmailOtpProperties {

    private String resendApiKey = "";
    private String fromAddress = "noreply@dony.app";
    private String otpTemplate = "Ton code dony est : %s. Valable 10 minutes.";

    public String getResendApiKey() { return resendApiKey; }
    public void setResendApiKey(String resendApiKey) { this.resendApiKey = resendApiKey; }
    public String getFromAddress() { return fromAddress; }
    public void setFromAddress(String fromAddress) { this.fromAddress = fromAddress; }
    public String getOtpTemplate() { return otpTemplate; }
    public void setOtpTemplate(String otpTemplate) { this.otpTemplate = otpTemplate; }
}
