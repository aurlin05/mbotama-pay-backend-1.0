package com.mbotamapay.controller;

import com.mbotamapay.config.SecurityConfig;
import com.mbotamapay.gateway.GatewayService;
import com.mbotamapay.repository.TransactionRepository;
import com.mbotamapay.service.JwtService;
import com.mbotamapay.service.TokenBlacklistService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Controller tests for PaymentController
 */
@WebMvcTest(PaymentController.class)
@Import(SecurityConfig.class)
class PaymentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private GatewayService gatewayService;

    @MockBean
    private TransactionRepository transactionRepository;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private UserDetailsService userDetailsService;

    @MockBean
    private TokenBlacklistService tokenBlacklistService;

    @Test
    @DisplayName("Get platforms should return available platforms")
    @WithMockUser(roles = "KYC_LEVEL_1")
    void getPlatforms_shouldReturnPlatforms() throws Exception {
        when(gatewayService.getAvailablePlatforms()).thenReturn(List.of("feexpay", "cinetpay", "paytech"));

        mockMvc.perform(get("/payments/platforms")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0]").value("feexpay"));
    }

    @Test
    @DisplayName("Get platforms should require authentication")
    void getPlatforms_shouldRequireAuth() throws Exception {
        mockMvc.perform(get("/payments/platforms")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }
}
