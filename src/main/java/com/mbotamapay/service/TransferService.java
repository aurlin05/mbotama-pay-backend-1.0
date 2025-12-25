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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * 2. Déterminer la route optimale
 * 3. Calculer les frais
 * 4. Créer la transaction
 * 5. Exécuter le payout
 * 6. Mettre à jour le stock si nécessaire
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TransferService {

    private final PaymentRoutingService routingService;
    private final FeeCalculator feeCalculator;
    private final TransactionLimitsService transactionLimitsService;
    private final TransactionRepository transactionRepository;
    private final UserRepository userRepository;
    private final GatewayStockRepository stockRepository;
    private final List<PayoutGateway> payoutGateways;

    /**
     * Exécute un transfert complet
     */
    @Transactional
    public TransferResult executeTransfer(Long userId, TransferRequest request) {
        log.info("Executing transfer: userId={}, recipient={}, amount={}",
                userId, request.getRecipientPhone(), request.getAmount());

        // 1. Valider l'utilisateur
        User sender = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Utilisateur non trouvé"));

        // 2. Déterminer la route
        RoutingDecision routing = routingService.determineRoute(
                request.getSenderPhone(),
                request.getRecipientPhone(),
                request.getAmount());

        if (!routing.isRouteFound()) {
            throw new BadRequestException("Aucune route disponible: " + routing.getRoutingReason());
        }

        // 3. Vérifier les limites (avec le nouveau service de limites qui inclut les limites par corridor)
        validateTransactionLimits(sender, request.getAmount(), routing);

        // 4. Créer la transaction
        String reference = generateReference();
        Transaction transaction = createTransaction(sender, request, routing, reference);
        transaction = transactionRepository.save(transaction);

        log.info("Transaction created: id={}, gateway={}, fees={}%",
                transaction.getId(), routing.getCollectionGateway(),
                routing.getFees().getDisplayPercent());

        // 5. Exécuter le payout
        PayoutResponse payoutResult = executePayout(routing, request, reference);

        // 6. Mettre à jour le statut
        if (payoutResult.isSuccess()) {
            transaction.setStatus(TransactionStatus.PENDING);
            transaction.setExternalReference(payoutResult.getExternalReference());

            // Débiter le stock si nécessaire
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
