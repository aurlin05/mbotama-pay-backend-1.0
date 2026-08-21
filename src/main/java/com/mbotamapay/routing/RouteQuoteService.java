package com.mbotamapay.routing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mbotamapay.entity.RouteQuote;
import com.mbotamapay.exception.BadRequestException;
import com.mbotamapay.repository.RouteQuoteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

/**
 * Émission et consommation des devis de routage.
 *
 * <p>
 * Le devis est le lien entre le prix affiché et le prix débité. Sans lui, la
 * prévisualisation et l'exécution sont deux décisions indépendantes prises à
 * deux instants différents, et rien ne garantit qu'elles concordent.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RouteQuoteService {

    private final RouteQuoteRepository repository;
    private final ObjectMapper objectMapper;

    @Value("${routing.quote.ttl-seconds:180}")
    private long ttlSeconds;

    @Value("${routing.quote.enabled:true}")
    private boolean enabled;

    /**
     * Fige une décision et retourne l'identifiant à présenter à l'exécution.
     *
     * @return vide si les devis sont désactivés ou si la décision n'est pas
     *         exécutable
     */
    @Transactional
    public Optional<RouteQuote> issue(RoutingDecision decision, RoutingContext request, Long userId) {
        if (!enabled || !decision.isExecutable()) {
            return Optional.empty();
        }
        Optional<ScoredRoute> selected = decision.selected();
        if (selected.isEmpty()) {
            return Optional.empty();
        }
        ScoredRoute route = selected.get();
        Instant now = Instant.now();

        RouteQuote quote = RouteQuote.builder()
                .id("Q" + UUID.randomUUID().toString().replace("-", ""))
                .userId(userId)
                .sourceCountry(request.sourceCountry())
                .destCountry(request.destCountry())
                .recipientPhone(request.recipientPhone())
                .amount(request.amount())
                .sourceCurrency(route.sourceCurrency())
                .payoutAmount(route.payoutAmount())
                .payoutCurrency(route.payoutCurrency())
                .totalFee(route.fees().getTotalFee())
                .displayPercent(route.fees().getDisplayPercent())
                .gateway(route.gateway())
                .decisionJson(serialise(decision))
                .createdAt(now)
                .expiresAt(now.plusSeconds(ttlSeconds))
                .build();

        return Optional.of(repository.save(quote));
    }

    /**
     * Consomme un devis pour une exécution.
     *
     * <p>
     * Vérifie que le devis existe, appartient bien à l'appelant, correspond à la
     * demande, n'est ni expiré ni déjà utilisé. Toute divergence est une erreur
     * explicite : c'est précisément le point du mécanisme.
     */
    @Transactional
    public RouteQuote consume(String quoteId, Long userId, String recipientPhone, long amount) {
        RouteQuote quote = repository.findById(quoteId)
                .orElseThrow(() -> new BadRequestException(
                        "Devis introuvable. Relancez une prévisualisation."));

        if (userId != null && quote.getUserId() != null && !userId.equals(quote.getUserId())) {
            // Ne pas révéler l'existence du devis d'un autre utilisateur.
            throw new BadRequestException("Devis introuvable. Relancez une prévisualisation.");
        }
        if (quote.isConsumed()) {
            throw new BadRequestException("Ce devis a déjà été utilisé.");
        }
        if (quote.isExpired()) {
            throw new BadRequestException(
                    "Devis expiré. Le prix a pu changer, relancez une prévisualisation.");
        }
        if (!quote.getRecipientPhone().equals(recipientPhone) || quote.getAmount() != amount) {
            throw new BadRequestException(
                    "Le devis ne correspond pas à la demande (bénéficiaire ou montant différent).");
        }

        quote.setConsumedAt(Instant.now());
        return repository.save(quote);
    }

    /** Purge des devis expirés. */
    @Scheduled(cron = "${routing.quote.cleanup-cron:0 15 * * * *}")
    @Transactional
    public void purgeExpired() {
        int removed = repository.deleteExpiredBefore(Instant.now().minus(1, ChronoUnit.DAYS));
        if (removed > 0) {
            log.info("Purged {} expired route quotes", removed);
        }
    }

    private String serialise(RoutingDecision decision) {
        try {
            return objectMapper.writeValueAsString(new DecisionAudit(
                    decision.corridor(),
                    decision.outcome().name(),
                    decision.explain(),
                    decision.fallbackOrder().stream().map(Enum::name).toList(),
                    decision.rejected().stream().map(r -> r.describe()).toList(),
                    decision.policyOverrides(),
                    decision.decisionTimeMs()));
        } catch (JsonProcessingException e) {
            // La sérialisation de l'audit ne doit jamais faire échouer un transfert.
            log.warn("Could not serialise routing decision for audit: {}", e.getMessage());
            return null;
        }
    }

    /** Forme figée de la décision, pour rester lisible même si le moteur évolue. */
    private record DecisionAudit(
            String corridor,
            String outcome,
            String explanation,
            java.util.List<String> fallbackOrder,
            java.util.List<String> rejections,
            java.util.List<String> policyOverrides,
            long decisionTimeMs) {
    }
}
