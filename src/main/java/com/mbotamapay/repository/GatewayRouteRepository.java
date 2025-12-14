package com.mbotamapay.repository;

import com.mbotamapay.entity.GatewayRoute;
import com.mbotamapay.entity.enums.Country;
import com.mbotamapay.entity.enums.GatewayType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository pour les règles de routage
 */
@Repository
public interface GatewayRouteRepository extends JpaRepository<GatewayRoute, Long> {

        /**
         * Trouve toutes les routes actives pour un couple source/destination
         * triées par priorité
         */
        @Query("SELECT gr FROM GatewayRoute gr " +
                        "WHERE gr.sourceCountry = :source AND gr.destCountry = :dest " +
                        "AND gr.enabled = true " +
                        "ORDER BY gr.priority ASC")
        List<GatewayRoute> findActiveRoutes(@Param("source") Country source, @Param("dest") Country dest);

        /**
         * Trouve la meilleure route (priorité la plus haute) pour un couple
         */
        Optional<GatewayRoute> findTopBySourceCountryAndDestCountryAndEnabledTrueOrderByPriorityAsc(
                        Country source, Country dest);

        /**
         * Trouve toutes les routes pour une passerelle donnée
         */
        List<GatewayRoute> findByGatewayAndEnabledTrue(GatewayType gateway);

        /**
         * Trouve toutes les routes depuis un pays source
         */
        List<GatewayRoute> findBySourceCountryAndEnabledTrue(Country source);

        /**
         * Vérifie si une route directe existe entre deux pays
         */
        @Query("SELECT COUNT(gr) > 0 FROM GatewayRoute gr " +
                        "WHERE gr.sourceCountry = :source AND gr.destCountry = :dest " +
                        "AND gr.enabled = true")
        boolean existsRoute(@Param("source") Country source, @Param("dest") Country dest);

        /**
         * Trouve les routes avec les frais les plus bas
         */
        @Query("SELECT gr FROM GatewayRoute gr " +
                        "WHERE gr.sourceCountry = :source AND gr.destCountry = :dest " +
                        "AND gr.enabled = true " +
                        "ORDER BY gr.gatewayFeePercent ASC")
        List<GatewayRoute> findCheapestRoutes(@Param("source") Country source, @Param("dest") Country dest);
}
