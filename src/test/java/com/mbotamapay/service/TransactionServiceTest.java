package com.mbotamapay.service;

import com.mbotamapay.dto.transaction.TransactionRequest;
import com.mbotamapay.dto.transaction.TransactionResponse;
import com.mbotamapay.entity.Transaction;
import com.mbotamapay.entity.User;
import com.mbotamapay.entity.enums.KycLevel;
import com.mbotamapay.entity.enums.TransactionStatus;
import com.mbotamapay.entity.enums.UserStatus;
import com.mbotamapay.exception.BadRequestException;
import com.mbotamapay.repository.TransactionRepository;
import com.mbotamapay.repository.UserRepository;
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
 * Unit tests for TransactionService
 */
@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private TransactionService transactionService;

    private User testUser;
    private TransactionRequest validRequest;
    private Transaction testTransaction;

    @BeforeEach
    void setUp() {
        testUser = User.builder()
                .id(1L)
                .phoneNumber("+22990000000")
                .firstName("Test")
                .lastName("User")
                .kycLevel(KycLevel.LEVEL_1)
                .status(UserStatus.ACTIVE)
                .build();

        validRequest = new TransactionRequest();
        validRequest.setRecipientPhone("+22990000001");
        validRequest.setRecipientName("Recipient");
        validRequest.setAmount(10000L);
        validRequest.setPlatform("feexpay");
        validRequest.setDescription("Test transaction");

        testTransaction = Transaction.builder()
                .id(1L)
                .sender(testUser)
                .senderPhone(testUser.getPhoneNumber())
                .senderName(testUser.getFullName())
                .recipientPhone("+22990000001")
                .recipientName("Recipient")
                .amount(10000L)
                .fee(100L)
                .currency("XOF")
                .platform("feexpay")
                .status(TransactionStatus.PENDING)
                .externalReference("MBP-TEST123")
                .createdAt(LocalDateTime.now())
                .build();
    }

    @Test
    @DisplayName("Initiate transaction should fail for user without KYC")
    void initiateTransaction_shouldFail_withoutKyc() {
        testUser.setKycLevel(KycLevel.NONE);

        when(userRepository.findById(anyLong())).thenReturn(Optional.of(testUser));

        assertThrows(BadRequestException.class,
                () -> transactionService.initiateTransaction(1L, validRequest));

        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("Initiate transaction should succeed for KYC Level 1 user within limits")
    void initiateTransaction_shouldSucceed_withKycLevel1() {
        when(userRepository.findById(anyLong())).thenReturn(Optional.of(testUser));
        when(transactionRepository.sumAmountBySenderIdAndStatusCompletedSince(anyLong(), any()))
                .thenReturn(0L);
        when(transactionRepository.save(any(Transaction.class))).thenReturn(testTransaction);

        TransactionResponse response = transactionService.initiateTransaction(1L, validRequest);

        assertNotNull(response);
        assertEquals(10000L, response.getAmount());
        verify(transactionRepository).save(any(Transaction.class));
    }

    @Test
    @DisplayName("Initiate transaction should fail when exceeding limit")
    void initiateTransaction_shouldFail_whenExceedingLimit() {
        // User has already used most of their limit
        when(userRepository.findById(anyLong())).thenReturn(Optional.of(testUser));
        when(transactionRepository.sumAmountBySenderIdAndStatusCompletedSince(anyLong(), any()))
                .thenReturn(490000L); // Near the 500k limit for Level 1

        validRequest.setAmount(20000L); // This would exceed the limit

        assertThrows(BadRequestException.class,
                () -> transactionService.initiateTransaction(1L, validRequest));
    }

    @Test
    @DisplayName("Get transaction should fail for non-owner")
    void getTransaction_shouldFail_forNonOwner() {
        User otherUser = User.builder().id(2L).build();
        testTransaction.setSender(otherUser);

        when(transactionRepository.findById(anyLong())).thenReturn(Optional.of(testTransaction));

        assertThrows(BadRequestException.class,
                () -> transactionService.getTransaction(1L, 1L));
    }

    @Test
    @DisplayName("Get transaction should succeed for owner")
    void getTransaction_shouldSucceed_forOwner() {
        when(transactionRepository.findById(anyLong())).thenReturn(Optional.of(testTransaction));

        TransactionResponse response = transactionService.getTransaction(1L, 1L);

        assertNotNull(response);
        assertEquals(testTransaction.getId(), response.getId());
    }

    @Test
    @DisplayName("Transaction fee should be calculated correctly")
    void transactionFee_shouldBeCalculatedCorrectly() {
        when(userRepository.findById(anyLong())).thenReturn(Optional.of(testUser));
        when(transactionRepository.sumAmountBySenderIdAndStatusCompletedSince(anyLong(), any()))
                .thenReturn(0L);
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(invocation -> {
            Transaction tx = invocation.getArgument(0);
            tx.setId(1L);
            return tx;
        });

        // For 10000 FCFA, fee should be 1% = 100 (minimum 100)
        validRequest.setAmount(10000L);
        TransactionResponse response = transactionService.initiateTransaction(1L, validRequest);

        assertEquals(100L, response.getFee());
        assertEquals(10100L, response.getTotalAmount());
    }
}
