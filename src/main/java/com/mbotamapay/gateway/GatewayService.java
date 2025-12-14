package com.mbotamapay.gateway;

import com.mbotamapay.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Gateway Service - Factory for selecting payment providers
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GatewayService {

    private final List<PaymentGateway> gateways;

    /**
     * Get gateway by platform name
     */
    public PaymentGateway getGateway(String platform) {
        return gateways.stream()
                .filter(gateway -> gateway.supports(platform))
                .findFirst()
                .orElseThrow(() -> new BadRequestException(
                        "Plateforme de paiement non supportée: " + platform));
    }

    /**
     * Get all available platforms
     */
    public List<String> getAvailablePlatforms() {
        return gateways.stream()
                .map(PaymentGateway::getPlatformName)
                .toList();
    }

    /**
     * Check if platform is supported
     */
    public boolean isPlatformSupported(String platform) {
        return gateways.stream()
                .anyMatch(gateway -> gateway.supports(platform));
    }
}
