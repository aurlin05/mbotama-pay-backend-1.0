package com.mbotamapay.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class SendGridEmailClient {
    @Value("${sendgrid.api-key:}")
    private String apiKey;

    public boolean sendHtml(String fromEmail, String fromName, String toEmail, String subject, String html) {
        if (apiKey == null || apiKey.isBlank()) {
            return false;
        }
        String body = """
                {
                  "personalizations": [
                    { "to": [ { "email": "%s" } ] }
                  ],
                  "from": { "email": "%s", "name": "%s" },
                  "subject": "%s",
                  "content": [
                    { "type": "text/html", "value": %s }
                  ]
                }
                """.formatted(
                escape(toEmail),
                escape(fromEmail),
                escape(fromName),
                escape(subject),
                toJsonString(html)
        );
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        HttpEntity<String> entity = new HttpEntity<>(body, headers);
        RestTemplate rt = new RestTemplate();
        try {
            ResponseEntity<String> res = rt.postForEntity("https://api.sendgrid.com/v3/mail/send", entity, String.class);
            return res.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            return false;
        }
    }

    private String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String toJsonString(String s) {
        return "\"" + escape(s) + "\"";
    }
}
