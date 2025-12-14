package com.mbotamapay.service;

import com.mbotamapay.dto.refund.RefundRequest;
import com.mbotamapay.dto.refund.RefundResponse;
import com.mbotamapay.entity.Refund;
import com.mbotamapay.entity.Transaction;
import com.mbotamapay.entity.User;
import com.mbotamapay.entity.enums.RefundStatus;
import com.mbotamapay.entity.enums.TransactionStatus;
import com.mbotamapay.exception.BadRequestException;
import com.mbotamapay.exception.ResourceNotFoundException;
import com.mbotamapay.repository.RefundRepository;
import com.mbotamapay.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Unit tests for RefundService
 */
@ExtendWith(MockitoExtension.class)
class RefundServiceTest {

    @Mock
    private RefundRepository refundRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private AuditService auditService;

    @InjectMocks
    private RefundService refundService;

    private User testUser;
    private Transaction completedTransaction;
    private RefundRequest validRequest;

    @BeforeEach
    void setUp() {
        testUser = User.builder()
                .id(1L)
                .phoneNumber("+22990000000")
                .firstName("Test")
                .lastName("User")
                .build();

        completedTransaction = Transaction.builder()
                .id(1L)
                .sender(testUser)
                .amount(10000L)
                .currency("XOF")
                .status(TransactionStatus.COMPLETED)
                .externalReference("MBP-TEST123")
                .completedAt(LocalDateTime.now().minusHours(1))
                .build();

        validRequest = new RefundRequest();
        validRequest.setReason("Transaction effectuée par erreur");
    }

    @Test
    @DisplayName("Initiate refund should succeed for completed transaction")
    void initiateRefund_shouldSucceed_forCompletedTransaction() {
        when(transactionRepository.findById(anyLong())).thenReturn(Optional.of(completedTransaction));
        when(refundRepository.existsByTransactionId(anyLong())).thenReturn(false);
        when(refundRepository.save(any(Refund.class))).thenAnswer(invocation -> {
            Refund refund = invocation.getArgument(0);
            refund.setId(1L);
            refund.setCreatedAt(LocalDateTime.now());
            return refund;
        });

        RefundResponse response = refundService.initiateRefund(1L, 1L, validRequest);

        assertNotNull(response);
        assertEquals(RefundStatus.PENDING, response.getStatus());
        assertEquals(10000L, response.getAmount());
        verify(refundRepository).save(any(Refund.class));
    }

    @Test
    @DisplayName("Initiate refund should fail for non-owner")
    void initiateRefund_shouldFail_forNonOwner() {
        User otherUser = User.builder().id(2L).build();
        completedTransaction.setSender(otherUser);

        when(transactionRepository.findById(anyLong())).thenReturn(Optional.of(completedTransaction));

        assertThrows(BadRequestException.class,
                () -> refundService.initiateRefund(1L, 1L, validRequest));
    }

    @Test
    @DisplayName("Initiate refund should fail for non-completed transaction")
    void initiateRefund_shouldFail_forNonCompletedTransaction() {
        completedTransaction.setStatus(TransactionStatus.PENDING);

        when(transactionRepository.findById(anyLong())).thenReturn(Optional.of(completedTransaction));

        assertThrows(BadRequestException.class,
                () -> refundService.initiateRefund(1L, 1L, validRequest));
    }

    @Test
    @DisplayName("Initiate refund should fail when refund already exists")
    void initiateRefund_shouldFail_whenRefundExists() {
        when(transactionRepository.findById(anyLong())).thenReturn(Optional.of(completedTransaction));
        when(refundRepository.existsByTransactionId(anyLong())).thenReturn(true);

        assertThrows(BadRequestException.class,
                () -> refundService.initiateRefund(1L, 1L, validRequest));
    }

    @Test
    @DisplayName("Initiate refund should fail after refund period")
    void initiateRefund_shouldFail_afterRefundPeriod() {
        completedTransaction.setCompletedAt(LocalDateTime.now().minusDays(10)); // 10 days ago

        when(transactionRepository.findById(anyLong())).thenReturn(Optional.of(completedTransaction));
        when(refundRepository.existsByTransactionId(anyLong())).thenReturn(false);

        assertThrows(BadRequestException.class,
                () -> refundService.initiateRefund(1L, 1L, validRequest));
    }

    @Test
    @DisplayName("Get refund should fail for non-existent refund")
    void getRefund_shouldFail_forNonExistentRefund() {
        when(refundRepository.findById(anyLong())).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> refundService.getRefund(1L, 999L));
    }
}
