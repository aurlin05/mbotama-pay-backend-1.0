package com.mbotamapay.dto.transfer;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Requête de transfert d'argent
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransferRequestDto {

    /**
     * Numéro de l'expéditeur, désormais <strong>indicatif</strong> : le serveur
     * retient celui du compte authentifié. Il déterminait le pays source, donc le
     * corridor, le barème et la piste d'audit ; le laisser au choix de l'appelant
     * revenait à lui laisser choisir son corridor.
     */
    private String senderPhone;

    /**
     * Code de l'opérateur source (ex: ORANGE_SN, WAVE_SN)
     * Mobile Money depuis lequel l'argent sera prélevé
     */
    @NotBlank(message = "L'opérateur source est requis")
    private String sourceOperator;

    @NotBlank(message = "Le numéro de téléphone du destinataire est requis")
    private String recipientPhone;

    @NotBlank(message = "Le nom du destinataire est requis")
    private String recipientName;

    /**
     * Code de l'opérateur destination (ex: MTN_BJ, MOOV_BJ)
     * Mobile Money vers lequel l'argent sera envoyé
     */
    @NotBlank(message = "L'opérateur destination est requis")
    private String destOperator;

    @NotNull(message = "Le montant est requis")
    @Min(value = 100, message = "Le montant minimum est de 100 XOF")
    private Long amount;

    private String description;

    /**
     * Devis émis par la prévisualisation.
     *
     * <p>
     * Facultatif, mais recommandé : sans lui l'exécution recalcule la route à son
     * propre instant et le prix débité peut différer du prix affiché. Fourni, il
     * est honoré ou l'appel échoue explicitement — jamais de reroutage silencieux.
     */
    private String quoteId;
}
