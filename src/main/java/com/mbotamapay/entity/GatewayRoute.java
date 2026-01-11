package com.mbotamapay.entity;

import com.mbotamapay.entity.enums.Country;
import com.mbotamapay.entity.enums.GatewayType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * Entité représentant une règle de routage entre deux pays
 * Définit quelle passerelle utiliser avec quelle priorité et quels frais
 */
@Entity
@Table(name = "gateway_routes", indexes = @Index(name = "idx_gateway_routes_countries", columnList = "source_country, dest_country, enabled"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GatewayRoute {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_country", nullable = false, length = 30)
    private Country sourceCountry;

    @Enumerated(EnumType.STRING)
    @Column(name = "dest_country", nullable = false, length = 30)
    private Country destCountry;

    @Enumerated(EnumType.STRING)
    @Column(name = "gateway", nullable = false, length = 20)
    private GatewayType gateway;

    /**
     * Priorité de la route (1 = plus haute priorité)
     */
    @Column(name = "priority", nullable = false)
    @Builder.Default
    private Integer priority = 1;

    /**
     * Frais combinés de la passerelle (Payin + Payout) en pourcentage
     */
    @Column(name = "gateway_fee_percent", nullable = false, precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal gatewayFeePercent = new BigDecimal("2.70");

    /**
     * Route active ou non
     */
    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    /**
     * Vérifie si cette route est pour le même pays (transfert local)
     */
    public boolean isLocalTransfer() {
        return sourceCountry == destCountry;
    }

    /**
     * Vérifie si cette route est cross-border
     */
    public boolean isCrossBorder() {
        return sourceCountry != destCountry;
    }
}
