package com.mbotamapay.config;

import com.mbotamapay.entity.GatewayRoute;
import com.mbotamapay.entity.GatewayStock;
import com.mbotamapay.entity.enums.Country;
import com.mbotamapay.entity.enums.GatewayType;
import com.mbotamapay.repository.GatewayRouteRepository;
import com.mbotamapay.repository.GatewayStockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Component
@ConditionalOnProperty(name = "routing.auto-config.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class RoutingAutoConfigurator {

    private final GatewayRouteRepository routeRepository;
    private final GatewayStockRepository stockRepository;

    @EventListener(ApplicationReadyEvent.class)
    public void initRoutingMatrix() {
        int createdRoutes = 0;
        int createdStocks = 0;

        Country[] countries = Country.values();
        GatewayType[] gateways = new GatewayType[] {
                GatewayType.FEEXPAY,
                GatewayType.CINETPAY,
                GatewayType.PAYTECH
        };

        // Ensure stocks exist for all gateway/country combinations
        for (GatewayType gateway : gateways) {
            for (Country country : countries) {
                if (stockRepository.findByGatewayAndCountry(gateway, country).isEmpty()) {
                    GatewayStock stock = GatewayStock.builder()
                            .gateway(gateway)
                            .country(country)
                            .balance(1_000_000L)
                            .minThreshold(100_000L)
                            .lastUpdated(LocalDateTime.now())
                            .build();
                    stockRepository.save(stock);
                    createdStocks++;
                }
            }
        }

        // Ensure routes exist for all source/dest combinations
        for (Country source : countries) {
            for (Country dest : countries) {
                if (!routeRepository.existsRoute(source, dest)) {
                    boolean local = source == dest;
                    // FEEXPAY primary
                    routeRepository.save(GatewayRoute.builder()
                            .sourceCountry(source)
                            .destCountry(dest)
                            .gateway(GatewayType.FEEXPAY)
                            .priority(1)
                            .gatewayFeePercent(local ? new BigDecimal("2.70") : new BigDecimal("2.70"))
                            .enabled(true)
                            .build());
                    createdRoutes++;
                    // CINETPAY secondary
                    routeRepository.save(GatewayRoute.builder()
                            .sourceCountry(source)
                            .destCountry(dest)
                            .gateway(GatewayType.CINETPAY)
                            .priority(2)
                            .gatewayFeePercent(local ? new BigDecimal("3.10") : new BigDecimal("3.50"))
                            .enabled(true)
                            .build());
                    createdRoutes++;
                    // PAYTECH tertiary
                    routeRepository.save(GatewayRoute.builder()
                            .sourceCountry(source)
                            .destCountry(dest)
                            .gateway(GatewayType.PAYTECH)
                            .priority(3)
                            .gatewayFeePercent(local ? new BigDecimal("3.50") : new BigDecimal("3.75"))
                            .enabled(true)
                            .build());
                    createdRoutes++;
                }
            }
        }

        log.info("RoutingAutoConfigurator initialized: createdRoutes={}, createdStocks={}", createdRoutes, createdStocks);
    }
}
