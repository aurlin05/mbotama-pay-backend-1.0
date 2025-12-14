package com.mbotamapay.dto.operator;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Information sur un opérateur Mobile Money
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OperatorInfoDto {

    /**
     * Code unique de l'opérateur (ex: ORANGE_SN, MTN_BJ)
     */
    private String code;

    /**
     * Nom affiché (ex: "Orange Money")
     */
    private String name;

    /**
     * Chemin vers le logo
     */
    private String logo;

    /**
     * Couleur de la marque (hex)
     */
    private String color;

    /**
     * Code pays ISO (ex: SN, BJ)
     */
    private String countryCode;
}
