package com.mbotamapay.entity;

import com.mbotamapay.entity.enums.Country;
import com.mbotamapay.entity.enums.GatewayType;
import com.mbotamapay.entity.enums.TransactionStatus;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * Transaction entity for money transfers
 */
@Entity
@Table(name = "transactions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sender_id")
    private User sender;

    @Column(name = "sender_phone", nullable = false, length = 20)
    private String senderPhone;

    @Column(name = "sender_name", length = 200)
    private String senderName;

    @Column(name = "recipient_phone", nullable = false, length = 20)
    private String recipientPhone;

    @Column(name = "recipient_name", length = 200)
    private String recipientName;

    @Column(name = "amount", nullable = false)
    private Long amount;

    @Column(name = "fee")
    @Builder.Default
    private Long fee = 0L;

    @Column(name = "currency", length = 5)
    @Builder.Default
    private String currency = "XOF";

    @Column(name = "platform", nullable = false, length = 50)
    private String platform;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private TransactionStatus status = TransactionStatus.PENDING;

    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "external_reference", length = 100)
    private String externalReference;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    // --- Routing fields ---

    @Enumerated(EnumType.STRING)
    @Column(name = "source_country", length = 5)
    private Country sourceCountry;

    @Enumerated(EnumType.STRING)
    @Column(name = "dest_country", length = 5)
    private Country destCountry;

    @Enumerated(EnumType.STRING)
    @Column(name = "collection_gateway", length = 20)
    private GatewayType collectionGateway;

    @Enumerated(EnumType.STRING)
    @Column(name = "payout_gateway", length = 20)
    private GatewayType payoutGateway;

    @Column(name = "used_stock")
    @Builder.Default
    private Boolean usedStock = false;

    @Column(name = "gateway_fee")
    @Builder.Default
    private Long gatewayFee = 0L;

    @Column(name = "app_fee")
    @Builder.Default
    private Long appFee = 0L;

    // --- Methods ---

    public Long getTotalAmount() {
        return amount + fee;
    }

    public Long getTotalFee() {
        return gatewayFee + appFee;
    }

    public boolean isLocalTransfer() {
        return sourceCountry != null && sourceCountry == destCountry;
    }
}
