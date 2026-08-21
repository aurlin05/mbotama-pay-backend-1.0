package com.mbotamapay.routing;

import com.mbotamapay.entity.enums.Country;
import com.mbotamapay.entity.enums.GatewayType;
import com.mbotamapay.gateway.PayoutGateway;
import com.mbotamapay.gateway.dto.PayoutRequest;
import com.mbotamapay.gateway.dto.PayoutResponse;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Exécution du versement, avec repli sur les passerelles suivantes.
 *
 * <p>
 * <strong>Aucune transaction base de données n'est ouverte ici.</strong> Le
 * repli enchaîne jusqu'à trois appels HTTP séquentiels ; les tenir dans une
 * transaction JPA immobilisait une connexion du pool pendant toute leur durée.
 *
 * <p>
 * Le repli n'est tenté que sur les échecs pour lesquels on sait que rien n'a été
 * engagé côté partenaire. Une expiration de délai ne rentre pas dans cette
 * catégorie : le versement a pu partir sans que la réponse nous parvienne.
 * Réessayer ailleurs dans ce cas revient à payer deux fois — c'est pourquoi
 * {@code retryOnTimeout} est faux par défaut.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PayoutExecutor {

    private final CapabilityMatrix matrix;
    private final GatewayHealthMonitor health;
    private final StockLedger stockLedger;

    @Value("${routing.execution.max-attempts:3}")
    private int maxAttempts;

    /**
     * Autoriser le repli après une expiration de délai. Faux par défaut : sans
     * idempotence de bout en bout, un réessai après timeout peut produire un
     * double versement.
     */
    @Value("${routing.execution.retry-on-timeout:false}")
    private boolean retryOnTimeout;

    public Result execute(RoutingDecision decision, PayoutRequest request, Country destCountry) {
        List<Attempt> failed = new ArrayList<>();
        List<GatewayType> order = decision.fallbackOrder();
        int limit = Math.min(order.size(), maxAttempts);

        for (int i = 0; i < limit; i++) {
            GatewayType gateway = order.get(i);

            // Le disjoncteur réserve ici la sonde — pas à la phase d'éligibilité,
            // sinon une simple prévisualisation consommerait la tentative de
            // récupération d'une passerelle en convalescence.
            if (!health.tryAcquire(gateway)) {
                failed.add(new Attempt(gateway, "disjoncteur fermé à l'appel", 0));
                continue;
            }

            Optional<PayoutGateway> impl = matrix.gateway(gateway);
            if (impl.isEmpty()) {
                failed.add(new Attempt(gateway, "implémentation absente", 0));
                continue;
            }

            long started = System.currentTimeMillis();
            try {
                PayoutResponse response = impl.get().initiatePayout(request);
                long elapsed = System.currentTimeMillis() - started;

                if (response.isSuccess()) {
                    health.recordSuccess(gateway, elapsed);
                    stockLedger.debit(gateway, destCountry, request.getAmount());

                    log.info("Payout succeeded via {} in {}ms (attempt {}/{})",
                            gateway, elapsed, i + 1, limit);

                    return Result.builder()
                            .success(true)
                            .gateway(gateway)
                            .response(response)
                            .attempts(i + 1)
                            .failedAttempts(List.copyOf(failed))
                            .elapsedMs(elapsed)
                            .build();
                }

                health.recordFailure(gateway, elapsed, response.getMessage());
                failed.add(new Attempt(gateway, response.getMessage(), elapsed));
                log.warn("Payout refused by {} in {}ms: {}", gateway, elapsed, response.getMessage());

            } catch (Exception e) {
                long elapsed = System.currentTimeMillis() - started;
                health.recordFailure(gateway, elapsed, e.getMessage());
                failed.add(new Attempt(gateway, e.getMessage(), elapsed));

                if (isTimeout(e) && !retryOnTimeout) {
                    log.error("Payout to {} timed out after {}ms — issue undetermined, no fallback attempted",
                            gateway, elapsed);
                    return Result.builder()
                            .success(false)
                            .gateway(gateway)
                            .attempts(i + 1)
                            .failedAttempts(List.copyOf(failed))
                            .elapsedMs(elapsed)
                            .outcomeUndetermined(true)
                            .errorMessage("Délai dépassé auprès de " + gateway.getDisplayName()
                                    + " : issue indéterminée, vérification manuelle requise")
                            .build();
                }
                log.error("Payout error via {} after {}ms: {}", gateway, elapsed, e.getMessage());
            }
        }

        return Result.builder()
                .success(false)
                .attempts(failed.size())
                .failedAttempts(List.copyOf(failed))
                .errorMessage(failed.isEmpty()
                        ? "Aucune passerelle disponible"
                        : "Échec après " + failed.size() + " tentative(s)")
                .build();
    }

    private boolean isTimeout(Throwable e) {
        Throwable current = e;
        while (current != null) {
            if (current instanceof java.net.SocketTimeoutException
                    || current instanceof org.springframework.web.client.ResourceAccessException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    @Builder
    public record Result(
            boolean success,
            GatewayType gateway,
            PayoutResponse response,
            int attempts,
            List<Attempt> failedAttempts,
            long elapsedMs,
            String errorMessage,
            boolean outcomeUndetermined) {
    }

    public record Attempt(GatewayType gateway, String reason, long elapsedMs) {
    }
}
