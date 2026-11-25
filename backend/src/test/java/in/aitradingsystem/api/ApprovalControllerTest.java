package in.aitradingsystem.api;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.aitradingsystem.common.PageResponse;
import in.aitradingsystem.paper.PaperOrderService;
import in.aitradingsystem.execution.OrderApprovalService;
import in.aitradingsystem.execution.OrderApprovalState;
import in.aitradingsystem.execution.OrderApprovalView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ApprovalControllerTest {
    private OrderApprovalService approvals;
    private PaperOrderService orders;
    private MockMvc mvc;
    private UUID approvalId;
    private OrderApprovalView pending;
    private OrderApprovalView approved;

    @BeforeEach
    void setup() {
        approvals = org.mockito.Mockito.mock(OrderApprovalService.class);
        orders = org.mockito.Mockito.mock(PaperOrderService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ApprovalController(approvals, orders)).build();
        approvalId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-30T00:00:00Z");
        pending = new OrderApprovalView(approvalId, orderId, policyId, 7, OrderApprovalState.PENDING,
            null, now, now.plusSeconds(300), null, null, 0, UUID.randomUUID(), "{}");
        approved = new OrderApprovalView(approvalId, orderId, policyId, 7, OrderApprovalState.APPROVED,
            "ok", now, now.plusSeconds(300), now.plusSeconds(1), "operator", 1,
            pending.riskDecisionId(), "{}");
    }

    @Test
    void listsPendingApprovalsWithPagination() throws Exception {
        when(approvals.list(OrderApprovalState.PENDING, 1, 10))
            .thenReturn(PageResponse.of(List.of(pending), 1, 10, 11));

        mvc.perform(get("/api/approvals").param("state", "PENDING").param("page", "1").param("size", "10"))
            .andExpect(status().isOk());

        verify(approvals).list(OrderApprovalState.PENDING, 1, 10);
    }

    @Test
    void getsApprovalDetail() throws Exception {
        when(approvals.require(approvalId)).thenReturn(pending);
        mvc.perform(get("/api/approvals/{id}", approvalId)).andExpect(status().isOk());
        verify(approvals).require(approvalId);
    }

    @Test
    void approveDelegatesToConcurrencySafeDecision() throws Exception {
        when(approvals.require(approvalId)).thenReturn(pending);
        when(orders.decideApproval(eq(approvalId), eq(0), eq(OrderApprovalState.APPROVED), eq("operator"), eq("ok")))
            .thenReturn(approved);

        mvc.perform(post("/api/approvals/{id}/approve", approvalId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedRowVersion\":0,\"actor\":\"operator\",\"reason\":\"ok\"}"))
            .andExpect(status().isOk());

        verify(orders).decideApproval(approvalId, 0, OrderApprovalState.APPROVED, "operator", "ok");
    }

    @Test
    void rejectAndCancelRoutesDelegateToMatchingDecisions() throws Exception {
        OrderApprovalView rejected = new OrderApprovalView(approvalId, pending.orderId(), pending.policyId(), 7,
            OrderApprovalState.REJECTED, "no", pending.requestedAt(), pending.expiresAt(), Instant.now(), "operator", 1,
            pending.riskDecisionId(), "{}");
        when(approvals.require(approvalId)).thenReturn(pending);
        when(orders.decideApproval(eq(approvalId), eq(0), eq(OrderApprovalState.REJECTED), eq("operator"), eq("no")))
            .thenReturn(rejected);
        mvc.perform(post("/api/approvals/{id}/reject", approvalId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedRowVersion\":0,\"actor\":\"operator\",\"reason\":\"no\"}"))
            .andExpect(status().isOk());

        OrderApprovalView cancelled = new OrderApprovalView(approvalId, pending.orderId(), pending.policyId(), 7,
            OrderApprovalState.CANCELLED, "cancel", pending.requestedAt(), pending.expiresAt(), Instant.now(), "operator", 1,
            pending.riskDecisionId(), "{}");
        when(orders.decideApproval(eq(approvalId), eq(0), eq(OrderApprovalState.CANCELLED), eq("operator"), eq("cancel")))
            .thenReturn(cancelled);
        mvc.perform(post("/api/approvals/{id}/cancel", approvalId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedRowVersion\":0,\"actor\":\"operator\",\"reason\":\"cancel\"}"))
            .andExpect(status().isOk());

        verify(orders).decideApproval(approvalId, 0, OrderApprovalState.REJECTED, "operator", "no");
        verify(orders).decideApproval(approvalId, 0, OrderApprovalState.CANCELLED, "operator", "cancel");
    }
}
