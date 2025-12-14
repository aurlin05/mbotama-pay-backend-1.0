package com.mbotamapay.controller;

import com.mbotamapay.dto.ApiResponse;
import com.mbotamapay.dto.operator.CountryOperatorsDto;
import com.mbotamapay.service.OperatorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/countries")
@RequiredArgsConstructor
@Tag(name = "Countries", description = "APIs pour les pays supportés")
public class CountryController {

    private final OperatorService operatorService;

    @GetMapping
    @Operation(summary = "Liste des pays", description = "Retourne la liste des pays supportés avec nom, drapeau et préfixe")
    public ResponseEntity<ApiResponse<List<CountryOperatorsDto.CountryInfo>>> getCountries() {
        List<CountryOperatorsDto.CountryInfo> countries = operatorService.getSupportedCountries();
        return ResponseEntity.ok(ApiResponse.success(countries));
    }
}
