package com.mbotamapay.routing;

import com.mbotamapay.entity.enums.GatewayType;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Disjoncteur et fenêtre d'observation par passerelle.
 *
 * <p>
 * La version précédente comportait trois défauts qui la rendaient inopérante :
 * <ul>
 * <li>un échec en état {@code HALF_OPEN} ne rouvrait pas le circuit — la
 * condition de réouverture exigeait {@code CLOSED} — donc le disjoncteur ne
 * pouvait se déclencher qu'une seule fois ;</li>
 * <li>{@code HALF_OPEN} laissait passer <em>tout</em> le trafic au lieu d'une
 * sonde unique, ce qui relançait la charge complète sur une passerelle qui
 * venait de tomber ;</li>
 * <li>lire l'état le modifiait : consulter le tableau de bord faisait basculer
 * le circuit en {@code HALF_OPEN}.</li>
 * </ul>
 *
 * <p>
 * La fenêtre glissante est désormais découpée en intervalles horodatés. Elle
 * décroissait auparavant selon l'ancienneté du <em>dernier succès</em>, si bien
 * qu'une passerelle en échec continu mais ayant réussi deux minutes plus tôt ne
 * voyait jamais ses compteurs vieillir.
 */
@Component
@Slf4j
public class GatewayHealthMonitor {

    private final Map<GatewayType, Health> health = new EnumMap<>(GatewayType.class);

    @Value("${routing.circuit.failure-threshold:5}")
    private int failureThreshold;

    @Value("${routing.circuit.recovery-timeout-ms:300000}")
    private long recoveryTimeoutMs;

    @Value("${routing.circuit.window-minutes:60}")
    private int windowMinutes;

    public GatewayHealthMonitor() {
        for (GatewayType gateway : GatewayType.values()) {
            health.put(gateway, new Health(gateway));
        }
    }

    public enum CircuitState {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    /**
     * Autorise ou non un appel, et réserve la sonde si le circuit est en
     * récupération.
     *
     * <p>
     * <strong>Cet appel a un effet de bord</strong> : il consomme le droit de
     * sonder. Il doit être appelé une fois par tentative réelle, jamais pour de
     * la consultation — utiliser {@link #state} pour lire sans réserver.
     */
    public boolean tryAcquire(GatewayType gateway) {
        return get(gateway).tryAcquire();
    }

    /**
     * Lecture pure de l'état. N'altère rien.
     */
    public CircuitState state(GatewayType gateway) {
        return get(gateway).state();
    }

    /**
     * Le circuit est-il en mesure d'accepter du trafic ? Lecture pure, utilisée
     * par la porte d'éligibilité pour écarter une passerelle sans consommer la
     * sonde.
     */
    public boolean isAvailable(GatewayType gateway) {
        return get(gateway).state() != CircuitState.OPEN;
    }

    public void recordSuccess(GatewayType gateway, long responseTimeMs) {
        get(gateway).recordSuccess(responseTimeMs);
    }

    public void recordFailure(GatewayType gateway, long responseTimeMs, String reason) {
        get(gateway).recordFailure(responseTimeMs, reason);
    }

    /** Observations retenues dans la fenêtre — sert au prior du scoring. */
    public Observations observations(GatewayType gateway) {
        return get(gateway).observations();
    }

    public Metrics metrics(GatewayType gateway) {
        return get(gateway).metrics();
    }

    public Map<GatewayType, Metrics> allMetrics() {
        Map<GatewayType, Metrics> out = new EnumMap<>(GatewayType.class);
        health.forEach((gateway, h) -> out.put(gateway, h.metrics()));
        return out;
    }

    /** Remise à zéro manuelle (administration). */
    public void reset(GatewayType gateway) {
        get(gateway).reset();
        log.info("Gateway {} circuit manually reset", gateway);
    }

    private Health get(GatewayType gateway) {
        return health.computeIfAbsent(gateway, Health::new);
    }

    /** Comptes bruts sur la fenêtre. */
    public record Observations(long successes, long failures, long avgLatencyMs) {
        public long total() {
            return successes + failures;
        }
    }

    @Builder
    public record Metrics(
            GatewayType gateway,
            CircuitState circuitState,
            boolean available,
            long successes,
            long failures,
            long consecutiveFailures,
            long avgLatencyMs,
            Instant lastSuccessAt,
            Instant lastFailureAt,
            String lastFailureReason) {
    }

    /**
     * État d'une passerelle. Les transitions passent toutes par un
     * {@code synchronized} court : le volume d'appels est de l'ordre de la
     * transaction, pas de la requête HTTP, la contention est négligeable et la
     * correction prime.
     */
    private final class Health {

        private static final int BUCKET_SECONDS = 60;

        private final GatewayType gateway;
        private final AtomicLong consecutiveFailures = new AtomicLong();
        private final AtomicBoolean probeInFlight = new AtomicBoolean(false);
        private final AtomicReference<Instant> lastSuccessAt = new AtomicReference<>();
        private final AtomicReference<Instant> lastFailureAt = new AtomicReference<>();
        private final AtomicReference<String> lastFailureReason = new AtomicReference<>();
        private volatile CircuitState circuit = CircuitState.CLOSED;
        private volatile Instant openedAt;

        private final Map<Long, Bucket> buckets = new java.util.concurrent.ConcurrentHashMap<>();

        Health(GatewayType gateway) {
            this.gateway = gateway;
        }

        synchronized boolean tryAcquire() {
            CircuitState current = state();
            switch (current) {
                case CLOSED:
                    return true;
                case HALF_OPEN:
                    // Une seule sonde à la fois. Les autres appels sont refusés jusqu'à
                    // ce que la sonde ait rendu son verdict.
                    return probeInFlight.compareAndSet(false, true);
                case OPEN:
                default:
                    return false;
            }
        }

        /** Lecture pure : calcule l'expiration du délai sans muter le circuit. */
        CircuitState state() {
            CircuitState current = circuit;
            if (current == CircuitState.OPEN
                    && openedAt != null
                    && Duration.between(openedAt, Instant.now()).toMillis() >= recoveryTimeoutMs) {
                return CircuitState.HALF_OPEN;
            }
            return current;
        }

        synchronized void recordSuccess(long responseTimeMs) {
            bucket().success(responseTimeMs);
            consecutiveFailures.set(0);
            lastSuccessAt.set(Instant.now());
            probeInFlight.set(false);
            if (circuit != CircuitState.CLOSED) {
                circuit = CircuitState.CLOSED;
                openedAt = null;
                log.info("Gateway {} circuit closed after successful probe", gateway);
            }
        }

        synchronized void recordFailure(long responseTimeMs, String reason) {
            // La latence des échecs compte aussi : une passerelle qui expire au bout de
            // quinze secondes est lente, même si l'appel n'aboutit pas.
            bucket().failure(responseTimeMs);
            lastFailureAt.set(Instant.now());
            lastFailureReason.set(reason);
            long consecutive = consecutiveFailures.incrementAndGet();

            boolean wasProbing = probeInFlight.getAndSet(false);
            CircuitState effective = state();

            if (effective == CircuitState.HALF_OPEN && wasProbing) {
                // La sonde a échoué : on rouvre franchement et on relance le délai.
                circuit = CircuitState.OPEN;
                openedAt = Instant.now();
                log.warn("Gateway {} probe failed, circuit re-opened: {}", gateway, reason);
                return;
            }

            if (circuit == CircuitState.CLOSED && consecutive >= failureThreshold) {
                circuit = CircuitState.OPEN;
                openedAt = Instant.now();
                log.warn("Gateway {} circuit opened after {} consecutive failures: {}",
                        gateway, consecutive, reason);
            }
        }

        Observations observations() {
            long cutoff = currentBucketId() - windowMinutes;
            long successes = 0;
            long failures = 0;
            long latencySum = 0;
            long latencyCount = 0;

            var iterator = buckets.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                if (entry.getKey() < cutoff) {
                    iterator.remove(); // purge paresseuse : pas de tâche planifiée requise
                    continue;
                }
                Bucket b = entry.getValue();
                successes += b.successes.get();
                failures += b.failures.get();
                latencySum += b.latencySum.get();
                latencyCount += b.latencyCount.get();
            }

            long avg = latencyCount == 0 ? 0 : latencySum / latencyCount;
            return new Observations(successes, failures, avg);
        }

        Metrics metrics() {
            Observations obs = observations();
            CircuitState current = state();
            return Metrics.builder()
                    .gateway(gateway)
                    .circuitState(current)
                    .available(current != CircuitState.OPEN)
                    .successes(obs.successes())
                    .failures(obs.failures())
                    .consecutiveFailures(consecutiveFailures.get())
                    .avgLatencyMs(obs.avgLatencyMs())
                    .lastSuccessAt(lastSuccessAt.get())
                    .lastFailureAt(lastFailureAt.get())
                    .lastFailureReason(lastFailureReason.get())
                    .build();
        }

        synchronized void reset() {
            buckets.clear();
            consecutiveFailures.set(0);
            probeInFlight.set(false);
            circuit = CircuitState.CLOSED;
            openedAt = null;
            lastFailureReason.set(null);
        }

        private Bucket bucket() {
            return buckets.computeIfAbsent(currentBucketId(), k -> new Bucket());
        }

        private long currentBucketId() {
            return Instant.now().getEpochSecond() / BUCKET_SECONDS;
        }
    }

    private static final class Bucket {
        private final AtomicLong successes = new AtomicLong();
        private final AtomicLong failures = new AtomicLong();
        private final AtomicLong latencySum = new AtomicLong();
        private final AtomicLong latencyCount = new AtomicLong();

        void success(long latencyMs) {
            successes.incrementAndGet();
            record(latencyMs);
        }

        void failure(long latencyMs) {
            failures.incrementAndGet();
            record(latencyMs);
        }

        private void record(long latencyMs) {
            if (latencyMs > 0) {
                latencySum.addAndGet(latencyMs);
                latencyCount.incrementAndGet();
            }
        }
    }
}
