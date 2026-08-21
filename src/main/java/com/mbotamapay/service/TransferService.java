package com.mbotamapay.service;

import com.mbotamapay.dto.FeeBreakdown;
import com.mbotamapay.entity.RouteQuote;
import com.mbotamapay.entity.Transaction;
import com.mbotamapay.entity.User;
import com.mbotamapay.entity.enums.TransactionStatus;
import com.mbotamapay.exception.BadRequestException;
import com.mbotamapay.exception.ResourceNotFoundException;
import com.mbotamapay.gateway.dto.PayoutRequest;
import com.mbotamapay.repository.TransactionRepository;
import com.mbotamapay.repository.UserRepository;
import com.mbotamapay.routing.*;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestration d'un transfert de bout en bout.
 *
 * <p>
 * Découpage transactionnel : l'intention est écrite et <strong>validée</strong>
 * avant l'appel partenaire, le résultat est écrit après. Les deux opérations ont
 * leur propre transaction courte. Auparavant l'ensemble — routage, contrôles,
 * appels HTTP, écritures — tenait dans une seule transaction JPA : une
 * exception après le versement annulait la ligne de transaction alors que
 * l'argent était parti, et il ne restait aucune trace.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TransferService {

    private final RoutingEngine routingEngine;
    private final RouteQuoteService quoteService;
    private final PayoutExecutor payoutExecutor;
    private final TransactionLimitsService transactionLimitsService;
    private final TransferLedger ledger;
    private final UserRepository userRepository;

    /**
     * Exige qu'un encaissement soit confirmé avant tout versement.
     *
     * <p>
     * Le flux ne comporte aujourd'hui aucune étape d'encaissement : chaque
     * transfert réussi est une sortie nette de trésorerie. Le garde-fou existe
     * pour être activé dès que l'encaissement sera branché ; il est faux par
     * défaut pour ne pas interrompre le service, et le démarrage le signale.
     */
    @Value("${transfer.require-collection-confirmed:false}")
    private boolean requireCollectionConfirmed;

    @PostConstruct
    void warnOnMissingCollection() {
        if (!requireCollectionConfirmed) {
            log.warn("⚠ transfer.require-collection-confirmed=false : les versements sont exécutés "
                    + "sans encaissement préalable de l'expéditeur. À activer dès que le flux "
                    + "d'encaissement est en place.");
        }
    }

    // ==================================================================
    // Prévisualisation
    // ==================================================================

    /**
     * Calcule la route et le prix sans rien exécuter, et émet un devis épinglé.
     *
     * <p>
     * Le devis lie le prix affiché au prix qui sera débité : sans lui, la
     * prévisualisation et l'exécution sont deux décisions indépendantes prises à
     * deux instants différents.
     */
    public TransferPreview previewTransfer(String senderPhone, String recipientPhone, Long amount) {
        return previewTransfer(senderPhone, recipientPhone, amount, null);
    }

    public TransferPreview previewTransfer(String senderPhone, String recipientPhone, Long amount, Long userId) {
        RoutingContext request;
        try {
            request = routingEngine.contextFor(senderPhone, recipientPhone, amount);
        } catch (NoRouteAvailableException e) {
            return TransferPreview.builder().available(false).reason(e.getMessage()).build();
        }

        RoutingDecision decision = routingEngine.decide(request);

        if (!decision.isExecutable()) {
            return TransferPreview.builder()
                    .available(false)
                    .reason(decision.explain())
                    .rejectionReasons(decision.rejected().stream()
                            .map(Eligibility.Rejection::describe)
                            .toList())
                    .sourceCountry(request.sourceCountry().getDisplayName())
                    .destCountry(request.destCountry().getDisplayName())
                    .build();
        }

        ScoredRoute best = decision.selected().orElseThrow();
        FeeBreakdown fees = best.fees();
        Optional<RouteQuote> quote = quoteService.issue(decision, request, userId);

        return TransferPreview.builder()
                .available(true)
                .amount(amount)
                .fee(fees.getTotalFee())
                .totalAmount(best.totalCharged())
                .displayFeePercent(fees.getDisplayPercent())
                .gatewayFee(fees.getGatewayFee())
                .appFee(fees.getAppFee())
                .gateway(best.gateway().getDisplayName())
                .sourceCountry(request.sourceCountry().getDisplayName())
                .destCountry(request.destCountry().getDisplayName())
                .sourceCurrency(best.sourceCurrency())
                .payoutAmount(best.payoutAmount())
                .payoutCurrency(best.payoutCurrency())
                .routingScore(best.totalScore())
                .routingStrategy(decision.outcome().name())
                .fallbackGateways(decision.fallbackOrder().stream()
                        .skip(1)
                        .map(g -> g.getDisplayName())
                        .toList())
                .quoteId(quote.map(RouteQuote::getId).orElse(null))
                .quoteExpiresAt(quote.map(q -> q.getExpiresAt().toString()).orElse(null))
                .reason(decision.explain())
                .build();
    }

    // ==================================================================
    // Exécution
    // ==================================================================

    public TransferResult executeTransfer(Long userId, TransferRequest request) {
        User sender = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Utilisateur non trouvé"));

        // Le numéro de l'expéditeur vient du compte authentifié, jamais du corps de
        // la requête : il détermine le pays source, donc le corridor, le barème et
        // la piste d'audit.
        String senderPhone = sender.getPhoneNumber();
        if (request.getSenderPhone() != null && !request.getSenderPhone().equals(senderPhone)) {
            log.warn("Sender phone in payload ({}) differs from authenticated account ({}), account wins",
                    request.getSenderPhone(), senderPhone);
        }

        RoutingContext context = routingEngine.contextFor(
                senderPhone, request.getRecipientPhone(), request.getAmount());

        // Devis : si l'appelant en présente un, il est honoré ou l'appel échoue.
        // Pas de reroutage silencieux à un autre prix.
        if (request.getQuoteId() != null && !request.getQuoteId().isBlank()) {
            quoteService.consume(request.getQuoteId(), userId,
                    request.getRecipientPhone(), request.getAmount());
        }

        RoutingDecision decision = routingEngine.decideOrThrow(context);

        transactionLimitsService.validateTransaction(
                sender, request.getAmount(), context.sourceCountry(), context.destCountry());

        if (requireCollectionConfirmed) {
            throw new BadRequestException(
                    "Encaissement préalable requis : le flux de collecte n'est pas encore branché.");
        }

        ScoredRoute route = decision.selected().orElseThrow();
        String reference = generateReference();

        // --- Transaction courte n°1 : l'intention, committée avant tout appel ---
        Transaction transaction = ledger.recordIntent(sender, senderPhone,
                request.getRecipientPhone(), request.getRecipientName(),
                request.getDescription(), context, route, reference);

        // --- Hors transaction : l'appel partenaire ---
        PayoutRequest payoutRequest = PayoutRequest.builder()
                .reference(reference)
                .amount(route.payoutAmount())
                .currency(route.payoutCurrency())
                .recipientPhone(request.getRecipientPhone())
                .recipientName(request.getRecipientName())
                .country(context.destCountry())
                .operator(context.destOperator())
                .description(request.getDescription())
                .build();

        PayoutExecutor.Result execution = payoutExecutor.execute(
                decision, payoutRequest, context.destCountry());

        // --- Transaction courte n°2 : le résultat ---
        Transaction current = ledger.recordOutcome(transaction.getId(), execution);

        return TransferResult.builder()
                .success(execution.success())
                .transactionId(current.getId())
                .reference(current.getExternalReference())
                .amount(route.sourceAmount())
                .fee(route.fees().getTotalFee())
                .totalAmount(route.totalCharged())
                .displayFeePercent(route.fees().getDisplayPercent())
                .status(current.getStatus().name())
                .routingReason(decision.explain())
                .message(execution.success()
                        ? "Transfert initié avec succès"
                        : execution.errorMessage())
                .gateway(execution.gateway() != null ? execution.gateway().getDisplayName() : null)
                .sourceCountry(context.sourceCountry().getDisplayName())
                .destCountry(context.destCountry().getDisplayName())
                .payoutAmount(route.payoutAmount())
                .payoutCurrency(route.payoutCurrency())
                .outcomeUndetermined(execution.outcomeUndetermined())
                .build();
    }

    private String generateReference() {
        // UUID complet : l'ancienne troncature à 8 caractères hexadécimaux ne
        // laissait que 32 bits, soit une collision sur deux vers 77 000 transferts,
        // alors que les callbacks retrouvent la transaction par cette référence.
        return "TRF-" + UUID.randomUUID();
    }

    // ==================================================================
    // Types d'échange
    // ==================================================================

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
        /** Devis émis par la prévisualisation. Facultatif. */
        private String quoteId;
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
        private Long payoutAmount;
        private String payoutCurrency;
        /** Vrai si l'appel a expiré : l'issue réelle du versement est inconnue. */
        private boolean outcomeUndetermined;
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
        private String reason;
        /** Motifs détaillés quand aucune route n'est disponible. */
        private List<String> rejectionReasons;
        private String routingStrategy;
        private Integer routingScore;
        private List<String> fallbackGateways;
        private String sourceCurrency;
        private Long payoutAmount;
        private String payoutCurrency;
        private String quoteId;
        private String quoteExpiresAt;
    }
}
