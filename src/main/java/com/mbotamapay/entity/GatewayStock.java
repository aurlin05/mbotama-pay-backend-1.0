package com.mbotamapay.entity;

import com.mbotamapay.entity.enums.Country;
import com.mbotamapay.entity.enums.GatewayType;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * Entité représentant le stock de fonds disponible par passerelle et pays
 * Utilisé pour le routage intelligent quand une transaction cross-gateway est
 * nécessaire
 */
@Entity
@Table(name = "gateway_stocks", uniqueConstraints = @UniqueConstraint(columnNames = { "gateway", "country" }))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class GatewayStock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "gateway", nullable = false, length = 20)
    private GatewayType gateway;

    @Enumerated(EnumType.STRING)
    @Column(name = "country", nullable = false, length = 5)
    private Country country;

    /**
     * Solde disponible en XOF
     */
    @Column(name = "balance", nullable = false)
    @Builder.Default
    private Long balance = 0L;

    /**
     * Seuil minimum avant alerte
     */
    @Column(name = "min_threshold", nullable = false)
    @Builder.Default
    private Long minThreshold = 100000L;

    @LastModifiedDate
    @Column(name = "last_updated", nullable = false)
    private LocalDateTime lastUpdated;

    /**
     * Vérifie si le stock est suffisant pour un montant donné
     */
    public boolean hasSufficientBalance(Long amount) {
        return balance >= amount;
    }

    /**
     * Vérifie si le stock est sous le seuil d'alerte
     */
    public boolean isBelowThreshold() {
        return balance < minThreshold;
    }

    /**
     * Débite le stock
     */
    public void debit(Long amount) {
        if (amount > balance) {
            throw new IllegalStateException("Solde insuffisant: " + balance + " < " + amount);
        }
        this.balance -= amount;
    }

    /**
     * Crédite le stock
     */
    public void credit(Long amount) {
        this.balance += amount;
    }
}
