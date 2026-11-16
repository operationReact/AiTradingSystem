package in.aitradingsystem.tradingrooms;

import static org.assertj.core.api.Assertions.assertThat;

import in.aitradingsystem.security.AccountApiOnlyFilter;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;

/** Negative security proof: no new public broker order or write route. */
class CustomerPaperFnoReconciliationRouteTest {
    private static final String PATH="/api/customer/trading-rooms/"
            +UUID.fromString("11111111-1111-4111-8111-111111111111")
            +"/paper/fno/reconciliation";

    @Test
    void readOnlyRouteIsAllowedButEveryMutatingMethodIsDeniedByAccountEdge()
            throws Exception {
        for (String method : new String[]{"GET","POST","PUT","PATCH","DELETE"}) {
            var request=new MockHttpServletRequest(method,PATH);
            var response=new MockHttpServletResponse();
            var chain=new MockFilterChain();
            new AccountApiOnlyFilter().doFilter(request,response,chain);
            if ("GET".equals(method)) {
                assertThat(chain.getRequest()).isNotNull();
                assertThat(response.getStatus()).isEqualTo(200);
            } else {
                assertThat(chain.getRequest()).isNull();
                assertThat(response.getStatus()).isEqualTo(404);
            }
        }
    }

    @Test
    void walletReconciliationIsReadOnlyAtThePublicAccountEdge() throws Exception {
        String path=PATH.replace("/reconciliation","/wallet-reconciliation");
        for (String method : new String[]{"GET","POST","PUT","PATCH","DELETE"}) {
            var request=new MockHttpServletRequest(method,path);
            var response=new MockHttpServletResponse();
            var chain=new MockFilterChain();
            new AccountApiOnlyFilter().doFilter(request,response,chain);
            if("GET".equals(method)) {
                assertThat(response.getStatus()).isEqualTo(200);
                assertThat(chain.getRequest()).isNotNull();
            } else {
                assertThat(response.getStatus()).isEqualTo(404);
                assertThat(chain.getRequest()).isNull();
            }
        }
        var method=CustomerPaperFnoReconciliationController.class.getMethod(
                "walletReconciliation",UUID.class,
                org.springframework.security.core.Authentication.class);
        assertThat(method.getAnnotation(GetMapping.class).value())
                .containsExactly("/wallet-reconciliation");
    }

    @Test
    void readOnlyLedgerEndpointsDoNotDependOnFnoTradingFlags() throws Exception {
        for (var type : new Class<?>[]{
                CustomerPaperFnoReconciliationController.class,
                CustomerPaperFnoReconciliationService.class,
                CustomerPaperSharedWalletAuditService.class,
                CustomerPaperFnoHistoryController.class}) {
            assertThat(type.getAnnotation(ConditionalOnProperty.class))
                .as(type.getSimpleName()).isNull();
        }
        var method=CustomerPaperFnoReconciliationController.class.getMethod(
                "receipt",UUID.class,org.springframework.security.core.Authentication.class);
        assertThat(method.getAnnotation(GetMapping.class).value())
                .containsExactly("/reconciliation");
        for (var name : new String[]{"orders","positions"}) {
            var view=CustomerPaperFnoHistoryController.class.getMethod(
                name,UUID.class,org.springframework.security.core.Authentication.class);
            assertThat(view.getAnnotation(GetMapping.class).value())
                .containsExactly("/"+name);
        }
        // Trading remains independently feature gated. No duplicate GET
        // handler is registered on the manual-order controller.
        assertThat(CustomerPaperFnoOrderController.class
            .getAnnotation(ConditionalOnProperty.class)).isNotNull();
        for (var methodOnTrading : CustomerPaperFnoOrderController.class.getMethods()) {
            assertThat(methodOnTrading.getAnnotation(GetMapping.class)).isNull();
        }
    }
}
