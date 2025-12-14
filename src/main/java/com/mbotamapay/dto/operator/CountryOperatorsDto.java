package com.mbotamapay.dto.operator;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Réponse contenant les opérateurs disponibles pour un pays
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CountryOperatorsDto {

    /**
     * Information sur le pays
     */
    private CountryInfo country;

    /**
     * Liste des opérateurs Mobile Money disponibles
     */
    private List<OperatorInfoDto> operators;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CountryInfo {
        private String code;
        private String name;
        private String flag;
        private String phonePrefix;
        private String currency;
    }
}
