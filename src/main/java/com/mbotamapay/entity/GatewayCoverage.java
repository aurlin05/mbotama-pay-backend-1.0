package com.mbotamapay.entity;

import com.mbotamapay.entity.enums.GatewayType;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Couverture d'une passerelle, redéfinie depuis l'administration.
 *
 * <p>
 * Troisième et dernier niveau de la chaîne de résolution :
 * <strong>base &gt; configuration &gt; défaut du code</strong>. Un champ nul
 * signifie « non redéfini à ce niveau » et laisse le niveau inférieur
 * s'appliquer — on peut donc ne changer que les pays de versement sans toucher
 * aux opérateurs ni aux devises.
 *
 * <p>
 * Persistée, et non tenue en mémoire : une couverture qui disparaîtrait au
 * redémarrage ou divergerait entre deux instances serait pire qu'une couverture
 * figée dans le code. C'est exactement le défaut qui rendait la liste de
 * révocation de jetons et les compteurs de débit inexploitables.
 */
@Entity
@Table(name = "gateway_coverage")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GatewayCoverage {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "gateway", nullable = false, length = 20)
    private GatewayType gateway;

    /** Codes pays séparés par des virgules, ex. {@code BENIN,TOGO}. */
    @Column(name = "collection_countries", length = 1000)
    private String collectionCountries;

    @Column(name = "payout_countries", length = 1000)
    private String payoutCountries;

    /** Codes devise ISO séparés par des virgules, ex. {@code XOF,XAF}. */
    @Column(name = "currencies", length = 200)
    private String currencies;

    /** Noms d'opérateurs séparés par des virgules, ex. {@code MTN_BJ,MOOV_BJ}. */
    @Column(name = "operators", length = 4000)
    private String operators;

    @Column(name = "supports_payout")
    private Boolean supportsPayout;

    /** Raison du changement, saisie par l'opérateur. */
    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by", length = 40)
    private String updatedBy;

    /** Aucun champ renseigné : la ligne n'a plus de raison d'être. */
    public boolean isEmpty() {
        return isBlank(collectionCountries)
                && isBlank(payoutCountries)
                && isBlank(currencies)
                && isBlank(operators)
                && supportsPayout == null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
