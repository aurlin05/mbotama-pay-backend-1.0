package com.mbotamapay.controller;

import com.mbotamapay.dto.ApiResponse;
import com.mbotamapay.dto.transfer.TransferPreviewRequestDto;
import com.mbotamapay.dto.transfer.TransferPreviewResponseDto;
import com.mbotamapay.dto.transfer.TransferRequestDto;
import com.mbotamapay.dto.transfer.TransferResponseDto;
import com.mbotamapay.entity.User;
import com.mbotamapay.entity.enums.MobileOperator;
import com.mbotamapay.service.OperatorService;
import com.mbotamapay.service.TransferService;
import com.mbotamapay.service.TransferService.TransferPreview;
import com.mbotamapay.service.TransferService.TransferRequest;
import com.mbotamapay.service.TransferService.TransferResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Controller pour les transferts d'argent avec routage intelligent
 * 
 * Endpoints:
 * - POST /transfers/preview : Prévisualise les frais et la route
 * - POST /transfers : Exécute le transfert
 */
@RestController
@RequestMapping("/transfers")
@RequiredArgsConstructor
@Tag(name = "Transfers", description = "APIs pour les transferts d'argent avec routage intelligent")
@SecurityRequirement(name = "bearerAuth")
@Slf4j
public class TransferController {

    private final TransferService transferService;
    private final OperatorService operatorService;

    /**
     * Prévisualise un transfert sans l'exécuter
     * Retourne les frais calculés et la route sélectionnée
     */
    @PostMapping("/preview")
    @Operation(summary = "Prévisualiser un transfert", description = "Calcule les frais et détermine la route optimale sans exécuter le transfert")
    public ResponseEntity<ApiResponse<TransferPreviewResponseDto>> previewTransfer(
            @Valid @RequestBody TransferPreviewRequestDto request) {

        log.info("Transfer preview: {} ({}) -> {} ({}), amount={}",
                request.getSenderPhone(), request.getSourceOperator(),
                request.getRecipientPhone(), request.getDestOperator(),
                request.getAmount());

        TransferPreview preview = transferService.previewTransfer(
                request.getSenderPhone(),
                request.getRecipientPhone(),
                request.getAmount());

        TransferPreviewResponseDto response = mapToPreviewResponse(preview,
                request.getSourceOperator(), request.getDestOperator());

        if (!preview.isAvailable()) {
            return ResponseEntity.ok(ApiResponse.success("Route non disponible", response));
        }

        return ResponseEntity.ok(ApiResponse.success("Preview calculé", response));
    }

    /**
     * Exécute un transfert complet
     * 1. Valide l'utilisateur
     * 2. Détermine la route optimale
     * 3. Calcule les frais
     * 4. Exécute le payout
     */
    @PostMapping
    @Operation(summary = "Exécuter un transfert", description = "Exécute un transfert d'argent avec routage intelligent automatique")
    @PreAuthorize("hasAnyRole('KYC_LEVEL_1', 'KYC_LEVEL_2')")
    public ResponseEntity<ApiResponse<TransferResponseDto>> executeTransfer(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody TransferRequestDto request) {

        log.info("Transfer execution: userId={}, {} ({}) -> {} ({}), amount={}",
                user.getId(), request.getSenderPhone(), request.getSourceOperator(),
                request.getRecipientPhone(), request.getDestOperator(),
                request.getAmount());

        TransferRequest transferRequest = TransferRequest.builder()
                .senderPhone(request.getSenderPhone())
                .sourceOperator(request.getSourceOperator())
                .recipientPhone(request.getRecipientPhone())
                .recipientName(request.getRecipientName())
                .destOperator(request.getDestOperator())
                .amount(request.getAmount())
                .description(request.getDescription())
                .build();

        TransferResult result = transferService.executeTransfer(user.getId(), transferRequest);

        TransferResponseDto response = mapToTransferResponse(result,
                request.getSourceOperator(), request.getDestOperator());

        if (!result.isSuccess()) {
            return ResponseEntity.ok(ApiResponse.error(result.getMessage()));
        }

        return ResponseEntity.ok(ApiResponse.success("Transfert initié avec succès", response));
    }

    // --- Mappers ---

    private TransferPreviewResponseDto mapToPreviewResponse(TransferPreview preview,
            String sourceOperator, String destOperator) {
        TransferPreviewResponseDto.TransferPreviewResponseDtoBuilder builder = TransferPreviewResponseDto.builder()
                .available(preview.isAvailable())
                .amount(preview.getAmount())
                .fee(preview.getFee())
                .totalAmount(preview.getTotalAmount())
                .feePercent(preview.getDisplayFeePercent())
                .gatewayFee(preview.getGatewayFee())
                .appFee(preview.getAppFee())
                .gateway(preview.getGateway())
                .sourceCountry(preview.getSourceCountry())
                .destCountry(preview.getDestCountry())
                .sourceOperatorName(getOperatorDisplayName(sourceOperator))
                .destOperatorName(getOperatorDisplayName(destOperator))
                .useStock(preview.isUseStock())
                .reason(preview.getReason())
                .routingStrategy(preview.getRoutingStrategy())
                .routingScore(preview.getRoutingScore())
                .fallbackGateways(preview.getFallbackGateways())
                .isBridgePayment(preview.isBridgePayment());

        // Bridge routing info
        if (preview.isBridgePayment() && preview.getBridgeLegs() != null) {
            builder.bridgeRoute(TransferPreviewResponseDto.BridgeRouteDto.builder()
                    .routeDescription(preview.getBridgeRouteDescription())
                    .bridgeCountries(preview.getBridgeCountries())
                    .hopCount(preview.getBridgeHopCount() != null ? preview.getBridgeHopCount() : 0)
                    .totalFeePercent(preview.getBridgeTotalFeePercent())
                    .legs(preview.getBridgeLegs().stream()
                            .map(leg -> TransferPreviewResponseDto.BridgeLegDto.builder()
                                    .from(leg.getFrom())
                                    .to(leg.getTo())
                                    .gateway(leg.getGateway())
                                    .feePercent(leg.getFeePercent())
                                    .build())
                            .collect(java.util.stream.Collectors.toList()))
                    .build());
        }

        return builder.build();
    }

    private TransferResponseDto mapToTransferResponse(TransferResult result,
            String sourceOperator, String destOperator) {
        return TransferResponseDto.builder()
                .success(result.isSuccess())
                .transactionId(result.getTransactionId())
                .reference(result.getReference())
                .amount(result.getAmount())
                .fee(result.getFee())
                .totalAmount(result.getTotalAmount())
                .feePercent(result.getDisplayFeePercent())
                .status(result.getStatus())
                .message(result.getMessage())
                .gateway(result.getGateway())
                .sourceCountry(result.getSourceCountry())
                .destCountry(result.getDestCountry())
                .sourceOperatorName(getOperatorDisplayName(sourceOperator))
                .destOperatorName(getOperatorDisplayName(destOperator))
                .build();
    }

    private String getOperatorDisplayName(String operatorCode) {
        if (operatorCode == null)
            return null;
        return operatorService.getOperatorByCode(operatorCode)
                .map(MobileOperator::getDisplayName)
                .orElse(operatorCode);
    }
}
