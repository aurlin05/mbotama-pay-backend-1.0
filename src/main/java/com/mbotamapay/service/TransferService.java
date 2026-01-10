package com.mbotamapay.service;

import com.mbotamapay.dto.FeeBreakdown;
import com.mbotamapay.dto.routing.RoutingDecision;
import com.mbotamapay.entity.GatewayStock;
import com.mbotamapay.entity.Transaction;
import com.mbotamapay.entity.User;
import com.mbotamapay.entity.enums.Country;
import com.mbotamapay.entity.enums.GatewayType;
import com.mbotamapay.entity.enums.MobileOperator;
import com.mbotamapay.entity.enums.TransactionStatus;
import com.mbotamapay.exception.BadRequestException;
import com.mbotamapay.exception.ResourceNotFoundException;
import com.mbotamapay.gateway.PayoutGateway;
import com.mbotamapay.gateway.dto.PayoutRequest;
import com.mbotamapay.gateway.dto.PayoutResponse;
import com.mbotamapay.repository.GatewayStockRepository;
import com.mbotamapay.repository.TransactionRepository;
import com.mbotamapay.repository.UserRepository;
import com.mbotamapay.service.orchestration.*;
import com.mbotamapay.service.orchestration.SmartPaymentOrchestrator.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service d'orchestration des transferts de bout en bout
 * 
 * Flux:
 * 1. Valider l'utilisateur et les limites
 * 2. Utiliser SmartPaymentOrchestrator pour le routage intelligent
 * 3. Exécuter avec fallback automatique
 * 4. Enregistrer les métriques
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TransferService {

    private final SmartPaymentOrchestrator orchestrator;
    private final RoutingAnalytics analytics;
    private final PaymentRoutingService routingService;
    private final FeeCalculator feeCalculator;
    private final TransactionLimitsService transactionLimitsService;
    private final TransactionRepository transactionRepository;
    private final UserRepository userRepository;
    private final GatewayStockRepository stockRepository;
    private final List<PayoutGateway> payoutGateways;

    @Value("${routing.use-smart-orchestrator:true}")
    private boolean useSmartOrchestrator;

    /**
     * Exécute un transfert complet avec orchestration intelligente
     */
    @Transactional
    public TransferResult executeTransfer(Long userId, TransferRequest request) {
        log.info("Executing transfer: userId={}, recipient={}, amount={}",
                userId, request.getRecipientPhone(), request.getAmount());

        // 1. Valider l'utilisateur
        User sender = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Utilisateur non trouvé"));

        // 2. Utiliser l'orchestrateur intelligent ou le routage classique
        if (useSmartOrchestrator) {
            return executeWithSmartOrchestrator(sender, request);
        } else {
            return executeWithClassicRouting(sender, request);
        }
    }

    /**
     * Exécution avec l'orchestrateur intelligent (fallback, scoring, métriques)
     */
    private TransferResult executeWithSmartOrchestrator(User sender, TransferRequest request) {
        // 1. Orchestrer le routage
        OrchestrationRequest orchRequest = OrchestrationRequest.builder()
                .senderPhone(request.getSenderPhone())
                .recipientPhone(request.getRecipientPhone())
                .recipientName(request.getRecipientName())
                .amount(request.getAmount())
                .currency("XOF")
                .description(request.getDescription())
                .build();

        OrchestrationResult orchestration = orchestrator.orchestrate(orchRequest);

        if (!orchestration.isSuccess()) {
            throw new BadRequestException("Routage impossible: " + orchestration.getErrorMessage());
        }

        // 2. Vérifier les limites
        transactionLimitsService.validateTransaction(
                sender,
                request.getAmount(),
                orchestration.getSourceCountry(),
                orchestration.getDestCountry()
        );

        // 3. Créer la transaction
        String reference = generateReference();
        Transaction transaction = createTransactionFromOrchestration(sender, request, orchestration, reference);
        transaction = transactionRepository.save(transaction);

        log.info("Transaction created: id={}, strategy={}, primaryGateway={}",
                transaction.getId(),
                orchestration.getStrategy().getType(),
                orchestration.getStrategy().getPrimaryGateway());

        // 4. Exécuter avec fallback automatique ou bridge
        PayoutRequest payoutRequest = buildPayoutRequest(request, orchestration, reference);
        PayoutExecutionResult execResult;
        
        if (orchestration.isBridgePayment()) {
            // Exécution bridge (multi-legs)
            log.info("Executing bridge payment: {}", orchestration.getBridgeRoute().getRouteDescription());
            execResult = orchestrator.executeBridgePayment(orchestration, payoutRequest);
        } else {
            // Exécution standard avec fallback
            execResult = orchestrator.executeWithFallback(orchestration, payoutRequest);
        }

        // 5. Mettre à jour la transaction et enregistrer les métriques
        if (execResult.isSuccess()) {
            transaction.setStatus(TransactionStatus.PENDING);
            if (execResult.getResponse() != null) {
                transaction.setExternalReference(execResult.getResponse().getExternalReference());
            }
            transaction.setPayoutGateway(execResult.getGateway());

            // Enregistrer le succès dans les analytics
            if (orchestration.isBridgePayment()) {
                analytics.recordBridgeSuccess(
                        orchestration.getSourceCountry(),
                        orchestration.getDestCountry(),
                        orchestration.getBridgeRoute().getBridgeCountries(),
                        request.getAmount(),
                        transaction.getFee(),
                        execResult.getExecutionTimeMs(),
                        orchestration.getBridgeRoute().getHopCount()
                );
            } else {
                analytics.recordSuccess(
                        execResult.getGateway(),
                        orchestration.getSourceCountry(),
                        orchestration.getDestCountry(),
                        request.getAmount(),
                        transaction.getFee(),
                        execResult.getExecutionTimeMs()
                );
            }

            // Log si fallback utilisé
            if (execResult.getAttemptNumber() > 1) {
                log.info("Transfer succeeded after {} attempts (fallback used)", execResult.getAttemptNumber());
                for (FailedAttempt failed : execResult.getFailedAttempts()) {
                    analytics.recordFallback(
                            failed.getGateway(),
                            execResult.getGateway(),
                            orchestration.getSourceCountry(),
                            orchestration.getDestCountry(),
                            failed.getReason()
                    );
                }
            }
        } else {
            transaction.setStatus(TransactionStatus.FAILED);
            transaction.setDescription("Payout failed after " + execResult.getTotalAttempts() + " attempts: " + 
                    execResult.getErrorMessage());

            // Enregistrer l'échec
            if (orchestration.isBridgePayment() && execResult.getBridgeLegResults() != null) {
                // Trouver le leg qui a échoué
                int failedLeg = execResult.getBridgeLegResults().stream()
                        .filter(leg -> !leg.isSuccess())
                        .findFirst()
                        .map(BridgeLegResult::getLegNumber)
                        .orElse(1);
                
                analytics.recordBridgeFailure(
                        orchestration.getSourceCountry(),
                        orchestration.getDestCountry(),
                        orchestration.getBridgeRoute().getBridgeCountries(),
                        request.getAmount(),
                        failedLeg,
                        execResult.getErrorMessage()
                );
            } else if (execResult.getFailedAttempts() != null) {
                for (FailedAttempt failed : execResult.getFailedAttempts()) {
                    analytics.recordFailure(
                            failed.getGateway(),
                            orchestration.getSourceCountry(),
                            orchestration.getDestCountry(),
                            request.getAmount(),
                            failed.getReason()
                    );
                }
            }
        }

        transactionRepository.save(transaction);

        return TransferResult.builder()
                .success(execResult.isSuccess())
                .transactionId(transaction.getId())
                .reference(reference)
                .amount(request.getAmount())
                .fee(transaction.getFee())
                .totalAmount(transaction.getTotalAmount())
                .displayFeePercent(orchestration.getFees() != null ? orchestration.getFees().getDisplayPercent() : 0)
                .status(transaction.getStatus().name())
                .routingReason(buildRoutingReason(orchestration, execResult))
                .message(execResult.isSuccess() ? "Transfert initié avec succès" : execResult.getErrorMessage())
                .gateway(execResult.getGateway() != null ? execResult.getGateway().getDisplayName() : null)
                .sourceCountry(orchestration.getSourceCountry().getDisplayName())
                .destCountry(orchestration.getDestCountry().getDisplayName())
                .build();
    }

    /**
     * Exécution classique (sans orchestrateur intelligent)
     */
    private TransferResult executeWithClassicRouting(User sender, TransferRequest request) {
        // Ancien code de routage
        RoutingDecision routing = routingService.determineRoute(
                request.getSenderPhone(),
                request.getRecipientPhone(),
                request.getAmount());

        if (!routing.isRouteFound()) {
            throw new BadRequestException("Aucune route disponible: " + routing.getRoutingReason());
        }

        validateTransactionLimits(sender, request.getAmount(), routing);

        String reference = generateReference();
        Transaction transaction = createTransaction(sender, request, routing, reference);
        transaction = transactionRepository.save(transaction);

        PayoutResponse payoutResult = executePayout(routing, request, reference);

        if (payoutResult.isSuccess()) {
            transaction.setStatus(TransactionStatus.PENDING);
            transaction.setExternalReference(payoutResult.getExternalReference());

            if (routing.isUseStock()) {
                debitStock(routing.getPayoutGateway(), routing.getDestCountry(), request.getAmount());
            }
        } else {
            transaction.setStatus(TransactionStatus.FAILED);
            transaction.setDescription("Payout failed: " + payoutResult.getMessage());
        }

        transactionRepository.save(transaction);

        return TransferResult.builder()
                .success(payoutResult.isSuccess())
                .transactionId(transaction.getId())
                .reference(reference)
                .amount(request.getAmount())
                .fee(transaction.getFee())
                .totalAmount(transaction.getTotalAmount())
                .displayFeePercent(routing.getFees().getDisplayPercent())
                .status(transaction.getStatus().name())
                .routingReason(routing.getRoutingReason())
                .message(payoutResult.isSuccess() ? "Transfert initié avec succès" : payoutResult.getMessage())
                .gateway(routing.getPayoutGateway() != null ? routing.getPayoutGateway().getDisplayName() : null)
                .sourceCountry(routing.getSourceCountry() != null ? routing.getSourceCountry().getDisplayName() : null)
                .destCountry(routing.getDestCountry() != null ? routing.getDestCountry().getDisplayName() : null)
                .build();
    }

    private Transaction createTransactionFromOrchestration(User sender, TransferRequest request,
            OrchestrationResult orchestration, String reference) {
        FeeBreakdown fees = orchestration.getFees();

        return Transaction.builder()
                .sender(sender)
                .senderPhone(request.getSenderPhone())
                .senderName(sender.getFullName())
                .recipientPhone(request.getRecipientPhone())
                .recipientName(request.getRecipientName())
                .amount(request.getAmount())
                .fee(fees != null ? fees.getTotalFee() : 0L)
                .gatewayFee(fees != null ? fees.getGatewayFee() : 0L)
                .appFee(fees != null ? fees.getAppFee() : 0L)
                .currency("XOF")
                .platform(orchestration.getStrategy().getPrimaryGateway().getCode())
                .status(TransactionStatus.PENDING)
                .description(request.getDescription())
                .externalReference(reference)
                .sourceCountry(orchestration.getSourceCountry())
                .destCountry(orchestration.getDestCountry())
                .collectionGateway(orchestration.getStrategy().getPrimaryGateway())
                .payoutGateway(orchestration.getStrategy().getPrimaryGateway())
                .usedStock(orchestration.getStrategy().isUseStock())
                .build();
    }

    private PayoutRequest buildPayoutRequest(TransferRequest request, OrchestrationResult orchestration, String reference) {
        return PayoutRequest.builder()
                .reference(reference)
                .amount(request.getAmount())
                .currency("XOF")
                .recipientPhone(request.getRecipientPhone())
                .recipientName(request.getRecipientName())
                .country(orchestration.getDestCountry())
                .operator(orchestration.getDestOperator())
                .description(request.getDescription())
                .build();
    }

    private String buildRoutingReason(OrchestrationResult orchestration, PayoutExecutionResult execResult) {
        StringBuilder sb = new StringBuilder();
        sb.append("Strategy: ").append(orchestration.getStrategy().getType());
        
        if (orchestration.isBridgePayment()) {
            sb.append(" | Bridge: ").append(orchestration.getBridgeRoute().getRouteDescription());
            sb.append(" | Hops: ").append(orchestration.getBridgeRoute().getHopCount());
            sb.append(" | Bridge Fee: ").append(orchestration.getBridgeRoute().getTotalFeePercent()).append("%");
        } else {
            sb.append(" | Score: ").append(orchestration.getStrategy().getPrimaryScore());
        }
        
        if (execResult.isSuccess()) {
            if (execResult.getGateway() != null) {
                sb.append(" | Gateway: ").append(execResult.getGateway());
            }
            if (execResult.getAttemptNumber() > 1) {
                sb.append(" (fallback après ").append(execResult.getAttemptNumber() - 1).append(" échec(s))");
            }
            if (execResult.getBridgeLegResults() != null && !execResult.getBridgeLegResults().isEmpty()) {
                sb.append(" | Legs: ").append(execResult.getBridgeLegResults().size());
            }
        }
        
        sb.append(" | Temps: ").append(execResult.getExecutionTimeMs()).append("ms");
        return sb.toString();
    }

    /**
     * Prévisualise un transfert sans l'exécuter
     */
    public TransferPreview previewTransfer(String senderPhone, String recipientPhone, Long amount) {
        RoutingDecision routing = routingService.determineRoute(senderPhone, recipientPhone, amount);

        if (!routing.isRouteFound()) {
            return TransferPreview.builder()
                    .available(false)
                    .reason(routing.getRoutingReason())
                    .build();
        }

        FeeBreakdown fees = routing.getFees();

        return TransferPreview.builder()
                .available(true)
                .amount(amount)
                .fee(fees.getTotalFee())
                .totalAmount(amount + fees.getTotalFee())
                .displayFeePercent(fees.getDisplayPercent())
                .gatewayFee(fees.getGatewayFee())
                .appFee(fees.getAppFee())
                .gateway(routing.getCollectionGateway().getDisplayName())
                .sourceCountry(routing.getSourceCountry().getDisplayName())
                .destCountry(routing.getDestCountry().getDisplayName())
                .useStock(routing.isUseStock())
                .build();
    }

    /**
     * Valider les limites de transaction (utilise le service de limites dédié)
     */
    private void validateTransactionLimits(User sender, Long amount, RoutingDecision routing) {
        // Utiliser le service de limites pour une validation complète
        // incluant les limites par transaction, quotidiennes, mensuelles et par corridor
        transactionLimitsService.validateTransaction(
                sender, 
                amount, 
                routing.getSourceCountry(), 
                routing.getDestCountry()
        );
        
        log.info("Transaction limits validated successfully for user={}, amount={}, corridor={}-{}", 
                sender.getId(), amount, routing.getSourceCountry(), routing.getDestCountry());
    }

    private Transaction createTransaction(User sender, TransferRequest request,
            RoutingDecision routing, String reference) {
        FeeBreakdown fees = routing.getFees();

        return Transaction.builder()
                .sender(sender)
                .senderPhone(request.getSenderPhone())
                .senderName(sender.getFullName())
                .recipientPhone(request.getRecipientPhone())
                .recipientName(request.getRecipientName())
                .amount(request.getAmount())
                .fee(fees.getTotalFee())
                .gatewayFee(fees.getGatewayFee())
                .appFee(fees.getAppFee())
                .currency("XOF")
                .platform(routing.getCollectionGateway().getCode())
                .status(TransactionStatus.PENDING)
                .description(request.getDescription())
                .externalReference(reference)
                .sourceCountry(routing.getSourceCountry())
                .destCountry(routing.getDestCountry())
                .collectionGateway(routing.getCollectionGateway())
                .payoutGateway(routing.getPayoutGateway())
                .usedStock(routing.isUseStock())
                .build();
    }

    private PayoutResponse executePayout(RoutingDecision routing, TransferRequest request, String reference) {
        PayoutGateway gateway = findPayoutGateway(routing.getPayoutGateway());

        Optional<Country> destCountry = Country.fromPhoneNumber(request.getRecipientPhone());
        Optional<MobileOperator> operator = destCountry.flatMap(
                c -> MobileOperator.fromPhoneNumber(request.getRecipientPhone(), c));

        PayoutRequest payoutRequest = PayoutRequest.builder()
                .reference(reference)
                .amount(request.getAmount())
                .currency("XOF")
                .recipientPhone(request.getRecipientPhone())
                .recipientName(request.getRecipientName())
                .country(destCountry.orElse(null))
                .operator(operator.orElse(null))
                .description(request.getDescription())
                .build();

        return gateway.initiatePayout(payoutRequest);
    }

    private PayoutGateway findPayoutGateway(GatewayType type) {
        return payoutGateways.stream()
                .filter(g -> g.getGatewayType() == type)
                .findFirst()
                .orElseThrow(() -> new BadRequestException(
                        "Passerelle de payout non disponible: " + type));
    }

    private void debitStock(GatewayType gateway, Country country, Long amount) {
        Optional<GatewayStock> stockOpt = stockRepository
                .findByGatewayAndCountryForUpdate(gateway, country);

        if (stockOpt.isPresent()) {
            GatewayStock stock = stockOpt.get();
            stock.debit(amount);
            stockRepository.save(stock);
            log.info("Stock debited: gateway={}, country={}, amount={}, newBalance={}",
                    gateway, country, amount, stock.getBalance());
        }
    }

    private String generateReference() {
        return "TRF" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    // --- Inner classes for request/response ---

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class TransferRequest {
        private String senderPhone;
        private String sourceOperator;
        private String recipientPhone;
        private String recipientName;
        private String destOperator;
        private Long amount;
        private String description;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class TransferResult {
        private boolean success;
        private Long transactionId;
        private String reference;
        private Long amount;
        private Long fee;
        private Long totalAmount;
        private Integer displayFeePercent;
        private String status;
        private String routingReason;
        private String message;
        private String gateway;
        private String sourceCountry;
        private String destCountry;
        private String sourceOperatorName;
        private String destOperatorName;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class TransferPreview {
        private boolean available;
        private Long amount;
        private Long fee;
        private Long totalAmount;
        private Integer displayFeePercent;
        private Long gatewayFee;
        private Long appFee;
        private String gateway;
        private String sourceCountry;
        private String destCountry;
        private String sourceOperatorName;
        private String destOperatorName;
        private boolean useStock;
        private String reason;
    }
}
