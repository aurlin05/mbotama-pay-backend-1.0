package com.mbotamapay.routing;

import com.mbotamapay.entity.enums.GatewayType;
import com.mbotamapay.routing.GatewayHealthMonitor.CircuitState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le disjoncteur comportait trois défauts qui le rendaient inopérant. Chacun a
 * son test ci-dessous.
 */
@DisplayName("Disjoncteur de passerelle")
class GatewayHealthMonitorTest {

    private static final GatewayType GATEWAY = GatewayType.FEEXPAY;

    private GatewayHealthMonitor monitor;

    @BeforeEach
    void setUp() {
        monitor = new GatewayHealthMonitor();
        ReflectionTestUtils.setField(monitor, "failureThreshold", 3);
        ReflectionTestUtils.setField(monitor, "recoveryTimeoutMs", 50L);
        ReflectionTestUtils.setField(monitor, "windowMinutes", 60);
    }

    private void fail(int times) {
        for (int i = 0; i < times; i++) {
            monitor.recordFailure(GATEWAY, 100, "boom");
        }
    }

    @Nested
    @DisplayName("Ouverture")
    class Opening {

        @Test
        @DisplayName("reste fermé sous le seuil d'échecs consécutifs")
        void staysClosedBelowThreshold() {
            fail(2);
            assertThat(monitor.state(GATEWAY)).isEqualTo(CircuitState.CLOSED);
            assertThat(monitor.isAvailable(GATEWAY)).isTrue();
        }

        @Test
        @DisplayName("s'ouvre au seuil atteint")
        void opensAtThreshold() {
            fail(3);
            assertThat(monitor.state(GATEWAY)).isEqualTo(CircuitState.OPEN);
            assertThat(monitor.isAvailable(GATEWAY)).isFalse();
            assertThat(monitor.tryAcquire(GATEWAY)).isFalse();
        }

        @Test
        @DisplayName("un succès remet le compteur d'échecs consécutifs à zéro")
        void successResetsConsecutiveFailures() {
            fail(2);
            monitor.recordSuccess(GATEWAY, 100);
            fail(2);
            assertThat(monitor.state(GATEWAY)).isEqualTo(CircuitState.CLOSED);
        }
    }

    @Nested
    @DisplayName("Récupération")
    class Recovery {

        @Test
        @DisplayName("passe en semi-ouvert après le délai de récupération")
        void movesToHalfOpenAfterTimeout() throws InterruptedException {
            fail(3);
            Thread.sleep(80);
            assertThat(monitor.state(GATEWAY)).isEqualTo(CircuitState.HALF_OPEN);
        }

        @Test
        @DisplayName("n'autorise qu'une seule sonde à la fois en semi-ouvert")
        void allowsASingleProbe() throws InterruptedException {
            fail(3);
            Thread.sleep(80);

            // La version précédente renvoyait true pour HALF_OPEN sans limitation :
            // tout le trafic repartait d'un coup sur une passerelle qui venait de
            // tomber.
            assertThat(monitor.tryAcquire(GATEWAY)).isTrue();
            assertThat(monitor.tryAcquire(GATEWAY)).isFalse();
            assertThat(monitor.tryAcquire(GATEWAY)).isFalse();
        }

        @Test
        @DisplayName("une sonde réussie referme le circuit")
        void successfulProbeCloses() throws InterruptedException {
            fail(3);
            Thread.sleep(80);
            monitor.tryAcquire(GATEWAY);
            monitor.recordSuccess(GATEWAY, 120);

            assertThat(monitor.state(GATEWAY)).isEqualTo(CircuitState.CLOSED);
            assertThat(monitor.tryAcquire(GATEWAY)).isTrue();
        }

        @Test
        @DisplayName("une sonde échouée rouvre franchement le circuit")
        void failedProbeReopens() throws InterruptedException {
            fail(3);
            Thread.sleep(80);
            monitor.tryAcquire(GATEWAY);
            monitor.recordFailure(GATEWAY, 100, "encore boom");

            // Défaut historique : recordFailure n'ouvrait que si l'état était CLOSED.
            // Depuis HALF_OPEN un échec ne rouvrait donc rien, et le circuit restait
            // passant — le disjoncteur ne pouvait se déclencher qu'une seule fois.
            assertThat(monitor.state(GATEWAY)).isEqualTo(CircuitState.OPEN);
            assertThat(monitor.tryAcquire(GATEWAY)).isFalse();
        }

        @Test
        @DisplayName("le délai de récupération repart à zéro après une sonde échouée")
        void failedProbeRestartsTimer() throws InterruptedException {
            fail(3);
            Thread.sleep(80);
            monitor.tryAcquire(GATEWAY);
            monitor.recordFailure(GATEWAY, 100, "encore boom");

            assertThat(monitor.state(GATEWAY)).isEqualTo(CircuitState.OPEN);
            Thread.sleep(80);
            assertThat(monitor.state(GATEWAY)).isEqualTo(CircuitState.HALF_OPEN);
        }
    }

    @Nested
    @DisplayName("Lectures")
    class Reads {

        @Test
        @DisplayName("consulter l'état ne consomme pas la sonde")
        void readingStateDoesNotConsumeProbe() throws InterruptedException {
            fail(3);
            Thread.sleep(80);

            // Défaut historique : getCircuitState() appelait isAvailable(), qui
            // faisait basculer le circuit. Ouvrir le tableau de bord modifiait donc
            // l'état du disjoncteur.
            monitor.state(GATEWAY);
            monitor.metrics(GATEWAY);
            monitor.isAvailable(GATEWAY);

            assertThat(monitor.tryAcquire(GATEWAY)).isTrue();
        }

        @Test
        @DisplayName("la latence des échecs est comptabilisée")
        void failureLatencyCounts() {
            // Une passerelle qui expire au bout de quinze secondes est lente, même
            // si l'appel n'aboutit pas. Seuls les succès étaient mesurés.
            monitor.recordFailure(GATEWAY, 15_000, "timeout");
            assertThat(monitor.observations(GATEWAY).avgLatencyMs()).isEqualTo(15_000);
        }

        @Test
        @DisplayName("les observations distinguent succès et échecs")
        void observationsSplit() {
            monitor.recordSuccess(GATEWAY, 200);
            monitor.recordSuccess(GATEWAY, 400);
            monitor.recordFailure(GATEWAY, 600, "boom");

            var observations = monitor.observations(GATEWAY);
            assertThat(observations.successes()).isEqualTo(2);
            assertThat(observations.failures()).isEqualTo(1);
            assertThat(observations.total()).isEqualTo(3);
            assertThat(observations.avgLatencyMs()).isEqualTo(400);
        }
    }
}
