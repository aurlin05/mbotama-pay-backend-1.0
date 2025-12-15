package com.mbotamapay.actuator;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

@Component("smtp")
@RequiredArgsConstructor
@Slf4j
public class SmtpHealthIndicator implements HealthIndicator {

    private final JavaMailSender mailSender;

    @Override
    public Health health() {
        long start = System.currentTimeMillis();
        try {
            if (mailSender instanceof JavaMailSenderImpl impl) {
                impl.testConnection();
                long ms = System.currentTimeMillis() - start;
                Health.Builder builder = Health.up()
                        .withDetail("host", impl.getHost())
                        .withDetail("port", impl.getPort())
                        .withDetail("username", impl.getUsername())
                        .withDetail("responseTimeMs", ms);
                return builder.build();
            } else {
                return Health.unknown().withDetail("error", "Unsupported mail sender").build();
            }
        } catch (Exception e) {
            log.error("SMTP health check failed: {}", e.getMessage());
            Health.Builder builder = Health.down()
                    .withDetail("error", e.getMessage());
            if (mailSender instanceof JavaMailSenderImpl impl) {
                builder.withDetail("host", impl.getHost())
                        .withDetail("port", impl.getPort())
                        .withDetail("username", impl.getUsername());
            }
            return builder.build();
        }
    }
}
