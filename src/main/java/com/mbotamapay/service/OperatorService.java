package com.mbotamapay.service;

import com.mbotamapay.dto.operator.CountryOperatorsDto;
import com.mbotamapay.dto.operator.OperatorInfoDto;
import com.mbotamapay.entity.enums.Country;
import com.mbotamapay.entity.enums.MobileOperator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Service pour récupérer les opérateurs Mobile Money disponibles
 */
@Service
@Slf4j
public class OperatorService {

    // Couleurs des opérateurs
    private static final Map<String, String> OPERATOR_COLORS = Map.ofEntries(
            Map.entry("ORANGE", "#FF6600"),
            Map.entry("MTN", "#FFCC00"),
            Map.entry("MOOV", "#6B2D91"),
            Map.entry("WAVE", "#1E90FF"),
            Map.entry("FREE", "#00CC66"),
            Map.entry("CELTIIS", "#FF0000"),
            Map.entry("TOGOCOM", "#0066CC"));

    // Logos des opérateurs
    private static final Map<String, String> OPERATOR_LOGOS = Map.ofEntries(
            Map.entry("ORANGE", "/logos/orange-money.png"),
            Map.entry("MTN", "/logos/mtn-momo.png"),
            Map.entry("MOOV", "/logos/moov-money.png"),
            Map.entry("WAVE", "/logos/wave.png"),
            Map.entry("FREE", "/logos/free-money.png"),
            Map.entry("CELTIIS", "/logos/celtiis.png"),
            Map.entry("TOGOCOM", "/logos/togocom.png"));

    // Drapeaux des pays
    private static final Map<Country, String> COUNTRY_FLAGS = Map.ofEntries(
            Map.entry(Country.SENEGAL, "🇸🇳"),
            Map.entry(Country.BENIN, "🇧🇯"),
            Map.entry(Country.TOGO, "🇹🇬"),
            Map.entry(Country.COTE_DIVOIRE, "🇨🇮"),
            Map.entry(Country.MALI, "🇲🇱"),
            Map.entry(Country.BURKINA_FASO, "🇧🇫"),
            Map.entry(Country.GUINEA, "🇬🇳"),
            Map.entry(Country.CAMEROON, "🇨🇲"),
            Map.entry(Country.NIGER, "🇳🇪"),
            Map.entry(Country.CONGO_BRAZZAVILLE, "🇨🇬"),
            Map.entry(Country.DRC, "🇨🇩"),
            Map.entry(Country.NIGERIA, "🇳🇬"));

    /**
     * Récupère les opérateurs disponibles à partir d'un numéro de téléphone
     */
    public CountryOperatorsDto getOperatorsByPhone(String phoneNumber) {
        log.info("Getting operators for phone: {}", phoneNumber);

        try {
            // Normaliser le numéro
            String normalized = normalizePhone(phoneNumber);
            log.info("Normalized phone: {}", normalized);

            // Détecter le pays
            Optional<Country> countryOpt = Country.fromPhoneNumber(normalized);
            if (countryOpt.isEmpty()) {
                log.warn("Country not found for phone: {}", phoneNumber);
                return CountryOperatorsDto.builder()
                        .country(null)
                        .operators(Collections.emptyList())
                        .build();
            }

            Country country = countryOpt.get();
            log.info("Detected country: {}", country);
            return getOperatorsByCountry(country);
        } catch (Exception e) {
            log.error("Error getting operators for phone: {}", phoneNumber, e);
            throw e;
        }
    }

    /**
     * Récupère les opérateurs disponibles pour un pays
     */
    public CountryOperatorsDto getOperatorsByCountry(Country country) {
        log.info("Getting operators for country: {}", country);

        try {
            // Récupérer tous les opérateurs de ce pays
            List<OperatorInfoDto> operators = Arrays.stream(MobileOperator.values())
                    .filter(op -> op.getCountry() == country)
                    .filter(op -> !op.getSupportedGateways().isEmpty()) // Seulement ceux avec MM
                    .map(op -> {
                        try {
                            return toOperatorInfo(op);
                        } catch (Exception e) {
                            log.error("Error converting operator {}: {}", op, e.getMessage());
                            throw e;
                        }
                    })
                    .collect(Collectors.toList());

            log.info("Found {} operators for country {}", operators.size(), country);

            CountryOperatorsDto.CountryInfo countryInfo = toCountryInfo(country);
            log.info("Country info created: {}", countryInfo);

            return CountryOperatorsDto.builder()
                    .country(countryInfo)
                    .operators(operators)
                    .build();
        } catch (Exception e) {
            log.error("Error getting operators for country: {}", country, e);
            throw e;
        }
    }

    public List<CountryOperatorsDto.CountryInfo> getSupportedCountries() {
        return Arrays.stream(Country.values())
                .map(this::toCountryInfo)
                .collect(Collectors.toList());
    }

    /**
     * Récupère les opérateurs disponibles pour un code pays ISO
     */
    public CountryOperatorsDto getOperatorsByCountryCode(String countryCode) {
        Optional<Country> countryOpt = Arrays.stream(Country.values())
                .filter(c -> c.getIsoCode().equalsIgnoreCase(countryCode))
                .findFirst();

        if (countryOpt.isEmpty()) {
            return CountryOperatorsDto.builder()
                    .country(null)
                    .operators(Collections.emptyList())
                    .build();
        }

        return getOperatorsByCountry(countryOpt.get());
    }

    /**
     * Vérifie si un opérateur est valide
     */
    public boolean isValidOperator(String operatorCode) {
        return Arrays.stream(MobileOperator.values())
                .anyMatch(op -> op.name().equalsIgnoreCase(operatorCode));
    }

    /**
     * Récupère un opérateur par son code
     */
    public Optional<MobileOperator> getOperatorByCode(String operatorCode) {
        return Arrays.stream(MobileOperator.values())
                .filter(op -> op.name().equalsIgnoreCase(operatorCode))
                .findFirst();
    }

    // --- Helpers ---

    private OperatorInfoDto toOperatorInfo(MobileOperator operator) {
        String baseName = getOperatorBaseName(operator.name());

        return OperatorInfoDto.builder()
                .code(operator.name())
                .name(operator.getDisplayName())
                .logo(OPERATOR_LOGOS.getOrDefault(baseName, "/logos/default.png"))
                .color(OPERATOR_COLORS.getOrDefault(baseName, "#888888"))
                .countryCode(operator.getCountry().getIsoCode())
                .build();
    }

    private CountryOperatorsDto.CountryInfo toCountryInfo(Country country) {
        return CountryOperatorsDto.CountryInfo.builder()
                .code(country.getIsoCode())
                .name(country.getDisplayName())
                .flag(COUNTRY_FLAGS.getOrDefault(country, "🏳️"))
                .phonePrefix("+" + country.getPhonePrefix())
                .currency(country.getCurrency())
                .build();
    }

    private String getOperatorBaseName(String operatorCode) {
        // ORANGE_SN -> ORANGE, MTN_BJ -> MTN
        int underscoreIndex = operatorCode.indexOf('_');
        if (underscoreIndex > 0) {
            return operatorCode.substring(0, underscoreIndex);
        }
        return operatorCode;
    }

    private String normalizePhone(String phone) {
        // Enlever les espaces, tirets, parenthèses
        String cleaned = phone.replaceAll("[\\s\\-()]", "");
        
        // Si commence par +, le garder
        if (cleaned.startsWith("+")) {
            return cleaned;
        }
        
        // Si commence par 00, remplacer par +
        if (cleaned.startsWith("00")) {
            return "+" + cleaned.substring(2);
        }
        
        // Sinon, ajouter +
        return "+" + cleaned;
    }
}
