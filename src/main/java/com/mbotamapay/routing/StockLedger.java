package com.mbotamapay.routing;

import com.mbotamapay.entity.GatewayStock;
import com.mbotamapay.entity.enums.Country;
import com.mbotamapay.entity.enums.GatewayType;
import com.mbotamapay.repository.GatewayStockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Mouvements de stock, isolés dans leurs propres transactions courtes.
 *
 * <p>
 * Le débit était auparavant effectué à l'intérieur de la transaction qui
 * englobait aussi les appels HTTP vers les partenaires : le verrou pessimiste
 * sur la ligne de stock était donc tenu pendant toute la durée de l'appel
 * réseau. Avec un pool de cinq connexions et des clients sans délai
 * d'expiration, un partenaire lent bloquait le corridor entier.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class StockLedger {

    private final GatewayStockRepository stockRepository;

    /**
     * Débite le stock d'une passerelle dans un pays. Transaction propre et courte,
     * détachée de l'appel partenaire.
     *
     * @return true si le débit a eu lieu
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean debit(GatewayType gateway, Country country, long amount) {
        Optional<GatewayStock> stockOpt = stockRepository.findByGatewayAndCountryForUpdate(gateway, country);
        if (stockOpt.isEmpty()) {
            return false;
        }
        GatewayStock stock = stockOpt.get();
        if (!stock.hasSufficientBalance(amount)) {
            log.warn("Stock debit refused: gateway={}, country={}, balance={}, requested={}",
                    gateway, country, stock.getBalance(), amount);
            return false;
        }
        stock.debit(amount);
        stockRepository.save(stock);
        log.info("Stock debited: gateway={}, country={}, amount={}, newBalance={}",
                gateway, country, amount, stock.getBalance());
        return true;
    }

    /** Recrédite après un versement finalement échoué. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void credit(GatewayType gateway, Country country, long amount) {
        stockRepository.findByGatewayAndCountryForUpdate(gateway, country).ifPresent(stock -> {
            stock.credit(amount);
            stockRepository.save(stock);
            log.info("Stock credited back: gateway={}, country={}, amount={}, newBalance={}",
                    gateway, country, amount, stock.getBalance());
        });
    }
}
