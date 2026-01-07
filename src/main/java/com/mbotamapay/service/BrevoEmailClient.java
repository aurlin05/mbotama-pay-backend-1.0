package com.mbotamapay.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Brevo (ex-Sendinblue) Email Client using HTTP API
 * Documentation: https://developers.brevo.com/reference/sendtransacemail
 */
@Component
@Slf4j
public class BrevoEmailClient {

    @Value("${brevo.api-key:}")
    private String apiKey;

    private static final String BREVO_API_URL = "https://api.brevo.com/v3/smtp/email";

    /**
     * Send HTML email via Brevo API
     * 
     * @param fromEmail sender email address
     * @param fromName  sender display name
     * @param toEmail   recipient email address
     * @param subject   email subject
     * @param html      HTML content
     * @return true if email was sent successfully
     */
    public boolean sendHtml(String fromEmail, String fromName, String toEmail, String subject, String html) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Brevo API key not configured");
            return false;
        }

        try {
            String body = """
                    {
                      "sender": { "email": "%s", "name": "%s" },
                      "to": [ { "email": "%s" } ],
                      "subject": "%s",
                      "htmlContent": %s
                    }
                    """.formatted(
                    escape(fromEmail),
                    escape(fromName),
                    escape(toEmail),
                    escape(subject),
                    toJsonString(html));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("api-key", apiKey);

            HttpEntity<String> entity = new HttpEntity<>(body, headers);
            RestTemplate rt = new RestTemplate();

            ResponseEntity<String> response = rt.postForEntity(BREVO_API_URL, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                log.debug("Brevo email sent successfully to {}", toEmail);
                return true;
            } else {
                log.warn("Brevo API returned status: {} - {}", response.getStatusCode(), response.getBody());
                return false;
            }
        } catch (Exception e) {
            log.error("Failed to send email via Brevo API to {}", toEmail, e);
            return false;
        }
    }

    private String escape(String s) {
        if (s == null)
            return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private String toJsonString(String s) {
        return "\"" + escape(s) + "\"";
    }
}
