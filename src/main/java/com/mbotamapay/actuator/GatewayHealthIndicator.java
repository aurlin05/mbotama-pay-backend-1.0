package com.mbotamapay.actuator;

import com.mbotamapay.gateway.GatewayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Gateway Health Indicator
 * Reports the health status of payment gateways
 */
@Component("paymentGateways")
@RequiredArgsConstructor
@Slf4j
public class GatewayHealthIndicator implements HealthIndicator {

    private final GatewayService gatewayService;

    @Override
    public Health health() {
        Map<String, Object> details = new HashMap<>();
        boolean anyDown = false;

        try {
            List<String> platforms = gatewayService.getAvailablePlatforms();
            details.put("availablePlatforms", platforms);
            details.put("platformCount", platforms.size());

            // Check each gateway
            for (String platform : platforms) {
                try {
                    boolean isHealthy = checkGatewayHealth(platform);
                    details.put(platform + "_status", isHealthy ? "UP" : "DOWN");
                    if (!isHealthy) {
                        anyDown = true;
                    }
                } catch (Exception e) {
                    details.put(platform + "_status", "DOWN");
                    details.put(platform + "_error", e.getMessage());
                    anyDown = true;
                }
            }

            if (anyDown) {
                return Health.down()
                        .withDetails(details)
                        .build();
            }

            return Health.up()
                    .withDetails(details)
                    .build();

        } catch (Exception e) {
            log.error("Failed to check gateway health: {}", e.getMessage());
            return Health.down()
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }

    /**
     * Check if a specific gateway is healthy
     * In production, this would ping the gateway's health endpoint
     */
    private boolean checkGatewayHealth(String platform) {
        try {
            // Get the gateway and verify it's configured
            gatewayService.getGateway(platform);
            return true;
        } catch (Exception e) {
            log.warn("Gateway {} is not healthy: {}", platform, e.getMessage());
            return false;
        }
    }
}
