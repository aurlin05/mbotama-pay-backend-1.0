package com.mbotamapay.entity;

import com.mbotamapay.entity.enums.Country;
import com.mbotamapay.entity.enums.GatewayType;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Devis de routage épinglé.
 *
 * <p>
 * La prévisualisation et l'exécution partagent le même moteur, mais pas le même
 * instant : un disjoncteur qui s'ouvre entre les deux change la route, donc le
 * prix. Le taux affiché étant un entier, le client pouvait voir 5 % puis payer
 * 6 %.
 *
 * <p>
 * Le devis fige la décision pour une durée courte. À l'exécution, soit il est
 * encore valide et le prix coté est honoré, soit l'appel échoue explicitement —
 * jamais de reroutage silencieux à un autre prix.
 */
@Entity
@Table(name = "route_quotes", indexes = {
        @Index(name = "idx_route_quotes_expires", columnList = "expires_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RouteQuote {

    @Id
    @Column(name = "id", length = 40)
    private String id;

    @Column(name = "user_id")
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_country", nullable = false, length = 30)
    private Country sourceCountry;

    @Enumerated(EnumType.STRING)
    @Column(name = "dest_country", nullable = false, length = 30)
    private Country destCountry;

    @Column(name = "recipient_phone", nullable = false, length = 20)
    private String recipientPhone;

    /** Montant envoyé, en unités mineures de {@link #source_currency}. */
    @Column(name = "amount", nullable = false)
    private Long amount;

    @Column(name = "source_currency", nullable = false, length = 5)
    private String sourceCurrency;

    @Column(name = "payout_amount", nullable = false)
    private Long payoutAmount;

    @Column(name = "payout_currency", nullable = false, length = 5)
    private String payoutCurrency;

    @Column(name = "total_fee", nullable = false)
    private Long totalFee;

    @Column(name = "display_percent", nullable = false)
    private Integer displayPercent;

    @Enumerated(EnumType.STRING)
    @Column(name = "gateway", nullable = false, length = 20)
    private GatewayType gateway;

    /** Trace complète de la décision, en JSON, pour l'audit. */
    @Lob
    @Column(name = "decision_json")
    private String decisionJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public boolean isConsumed() {
        return consumedAt != null;
    }

    public boolean isUsable() {
        return !isExpired() && !isConsumed();
    }
}
