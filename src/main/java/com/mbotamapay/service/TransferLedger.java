package com.mbotamapay.service;

import com.mbotamapay.dto.FeeBreakdown;
import com.mbotamapay.entity.Transaction;
import com.mbotamapay.entity.User;
import com.mbotamapay.entity.enums.TransactionStatus;
import com.mbotamapay.repository.TransactionRepository;
import com.mbotamapay.routing.PayoutExecutor;
import com.mbotamapay.routing.RoutingContext;
import com.mbotamapay.routing.ScoredRoute;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Écritures comptables d'un transfert, dans des transactions courtes et
 * indépendantes de l'appel partenaire.
 *
 * <p>
 * Service distinct de {@link TransferService} à dessein : une méthode
 * {@code @Transactional} appelée depuis la même classe ne passe pas par le
 * proxy Spring et son annotation reste sans effet. Le découpage en deux
 * transactions n'a de valeur que s'il est réellement appliqué.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TransferLedger {

    private final TransactionRepository transactionRepository;

    /**
     * Écrit l'intention et la valide immédiatement, <em>avant</em> tout appel
     * partenaire.
     *
     * <p>
     * L'ancien découpage englobait le versement dans la même transaction : une
     * exception après l'appel annulait la ligne alors que l'argent était parti.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Transaction recordIntent(User sender, String senderPhone, String recipientPhone,
            String recipientName, String description, RoutingContext context,
            ScoredRoute route, String reference) {

        FeeBreakdown fees = route.fees();
        Transaction transaction = Transaction.builder()
                .sender(sender)
                .senderPhone(senderPhone)
                .senderName(sender.getFullName())
                .recipientPhone(recipientPhone)
                .recipientName(recipientName)
                .amount(context.amount())
                .fee(fees.getTotalFee())
                .gatewayFee(fees.getGatewayFee())
                .appFee(fees.getAppFee())
                .currency(route.sourceCurrency())
                .platform(route.gateway().getCode())
                .status(TransactionStatus.PENDING)
                .description(description)
                .externalReference(reference)
                .sourceCountry(context.sourceCountry())
                .destCountry(context.destCountry())
                .collectionGateway(route.gateway())
                .payoutGateway(route.gateway())
                .usedStock(false)
                .build();

        Transaction saved = transactionRepository.save(transaction);
        log.info("Transfer intent recorded: id={}, ref={}, corridor={}, gateway={}",
                saved.getId(), reference, context.corridor(), route.gateway());
        return saved;
    }

    /** Écrit le résultat du versement dans sa propre transaction. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Transaction recordOutcome(Long transactionId, PayoutExecutor.Result execution) {
        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new IllegalStateException(
                        "Transaction " + transactionId + " introuvable après versement"));

        if (execution.success()) {
            transaction.setStatus(TransactionStatus.PROCESSING);
            transaction.setPayoutGateway(execution.gateway());
            if (execution.response() != null && execution.response().getExternalReference() != null) {
                transaction.setExternalReference(execution.response().getExternalReference());
            }
        } else if (execution.outcomeUndetermined()) {
            // Ni réussi ni échoué : l'appel a expiré, le partenaire a pu exécuter.
            // Marquer FAILED ici serait affirmer une chose non vérifiée ; la
            // réconciliation tranchera en interrogeant le partenaire.
            transaction.setStatus(TransactionStatus.PROCESSING);
            transaction.setPayoutGateway(execution.gateway());
            transaction.setFailureReason(execution.errorMessage());
            log.error("Transfer {} left PROCESSING: payout outcome undetermined", transactionId);
        } else {
            transaction.setStatus(TransactionStatus.FAILED);
            transaction.setFailureReason(execution.errorMessage());
        }

        return transactionRepository.save(transaction);
    }
}
