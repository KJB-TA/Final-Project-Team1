package com.team1.settlement.controller;

import com.team1.security.AuthContext;
import com.team1.security.AuthenticatedUser;
import com.team1.settlement.dto.AdminSettlementResponse;
import com.team1.settlement.dto.SettlementPeriod;
import com.team1.settlement.service.SettlementService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AdminSettlementController.class)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = {
        "jwt.secret=test-secret-key-that-is-at-least-32-bytes-long",
        "internal.token=test-internal-token"
})
class AdminSettlementControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SettlementService settlementService;

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    @Test
    @DisplayName("SUPER_ADMIN이면 월간 정산 데이터를 조회한다")
    void superAdminCanViewMonthlySettlement() throws Exception {
        AuthContext.set(new AuthenticatedUser(1L, "SUPER_ADMIN"));
        when(settlementService.getSettlement(SettlementPeriod.MONTH, LocalDate.of(2026, 9, 15), false)).thenReturn(
                new AdminSettlementResponse(
                        SettlementPeriod.MONTH, "2026-09-01", "2026-09-30",
                        4500000, 150000, 4350000, 435000, 0.10,
                        4000000, 100000, 500000, 50000,
                        12, 1, 3, 1,
                        List.of(), List.of(), List.of(), null));

        mockMvc.perform(get("/api/v1/admin/settlement")
                        .param("period", "MONTH").param("date", "2026-09-15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRevenue").value(4500000))
                .andExpect(jsonPath("$.platformFee").value(435000))
                .andExpect(jsonPath("$.reservationRevenue").value(4000000))
                .andExpect(jsonPath("$.reservationRefund").value(100000))
                .andExpect(jsonPath("$.promotionRevenue").value(500000))
                .andExpect(jsonPath("$.promotionRefund").value(50000));
    }

    @Test
    @DisplayName("period=YEAR면 연간 정산 데이터를 조회한다")
    void superAdminCanViewYearlySettlement() throws Exception {
        AuthContext.set(new AuthenticatedUser(1L, "SUPER_ADMIN"));
        when(settlementService.getSettlement(SettlementPeriod.YEAR, LocalDate.of(2026, 1, 1), false)).thenReturn(
                new AdminSettlementResponse(
                        SettlementPeriod.YEAR, "2026-01-01", "2026-12-31",
                        54000000, 1800000, 52200000, 5220000, 0.10,
                        48000000, 1600000, 6000000, 200000,
                        120, 10, 30, 5,
                        List.of(), List.of(), List.of(), null));

        mockMvc.perform(get("/api/v1/admin/settlement")
                        .param("period", "YEAR").param("date", "2026-01-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period").value("YEAR"))
                .andExpect(jsonPath("$.totalRevenue").value(54000000))
                .andExpect(jsonPath("$.platformFee").value(5220000));
    }

    @Test
    @DisplayName("로그인 안 했으면 401 UNAUTHENTICATED - 프론트가 로그인 화면으로 보내는 형식이어야 한다")
    void unauthenticatedIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/admin/settlement")
                        .param("period", "MONTH").param("date", "2026-09-15"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("SUPER_ADMIN이 아니면 403")
    void nonAdminIsForbidden() throws Exception {
        AuthContext.set(new AuthenticatedUser(1L, "USER"));

        mockMvc.perform(get("/api/v1/admin/settlement")
                        .param("period", "MONTH").param("date", "2026-09-15"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.code").value("FORBIDDEN"));
    }
}
