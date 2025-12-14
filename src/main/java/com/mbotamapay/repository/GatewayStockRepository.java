package com.mbotamapay.repository;

import com.mbotamapay.entity.GatewayStock;
import com.mbotamapay.entity.enums.Country;
import com.mbotamapay.entity.enums.GatewayType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

/**
 * Repository pour la gestion des stocks par passerelle
 */
@Repository
public interface GatewayStockRepository extends JpaRepository<GatewayStock, Long> {

    /**
     * Trouve le stock pour une passerelle et un pays
     */
    Optional<GatewayStock> findByGatewayAndCountry(GatewayType gateway, Country country);

    /**
     * Trouve le stock avec verrouillage pour mise à jour atomique
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT gs FROM GatewayStock gs WHERE gs.gateway = :gateway AND gs.country = :country")
    Optional<GatewayStock> findByGatewayAndCountryForUpdate(GatewayType gateway, Country country);

    /**
     * Trouve tous les stocks pour un pays
     */
    List<GatewayStock> findByCountry(Country country);

    /**
     * Trouve tous les stocks pour une passerelle
     */
    List<GatewayStock> findByGateway(GatewayType gateway);

    /**
     * Trouve les stocks sous le seuil d'alerte
     */
    @Query("SELECT gs FROM GatewayStock gs WHERE gs.balance < gs.minThreshold")
    List<GatewayStock> findBelowThreshold();

    /**
     * Vérifie si le stock est suffisant
     */
    @Query("SELECT CASE WHEN gs.balance >= :amount THEN true ELSE false END " +
            "FROM GatewayStock gs WHERE gs.gateway = :gateway AND gs.country = :country")
    boolean hasSufficientBalance(GatewayType gateway, Country country, Long amount);
}
