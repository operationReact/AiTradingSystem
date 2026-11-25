package in.aitradingsystem.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.aitradingsystem.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@Import(AuthorizationMatrixIT.AuthorizationProbeController.class)
class AuthorizationMatrixIT extends AbstractPostgresIT {

    @Autowired MockMvc mvc;

    @Test
    void viewerCanReadGeneralApiButCannotReadAdminOrMutateProtectedRoutes() throws Exception {
        mvc.perform(get("/api/audit/__authz_probe").with(user("viewer").roles("VIEWER")))
            .andExpect(status().isOk());
        mvc.perform(get("/api/admin/__authz_probe").with(user("viewer").roles("VIEWER")))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/model-governance/__authz_probe").with(user("viewer").roles("VIEWER")))
            .andExpect(status().isForbidden());

        mvc.perform(post("/api/research/__authz_probe").with(user("viewer").roles("VIEWER")))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/risk/__authz_probe").with(user("viewer").roles("VIEWER")))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/unclassified/__authz_probe").with(user("viewer").roles("VIEWER")))
            .andExpect(status().isForbidden());
    }

    @Test
    void traderCanMutateTradingRoutesButCannotReadAdminOrMutateAdminRoutes() throws Exception {
        mvc.perform(get("/api/audit/__authz_probe").with(user("trader").roles("TRADER")))
            .andExpect(status().isOk());
        mvc.perform(get("/api/admin/__authz_probe").with(user("trader").roles("TRADER")))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/model-governance/__authz_probe").with(user("trader").roles("TRADER")))
            .andExpect(status().isForbidden());

        mvc.perform(post("/api/research/__authz_probe").with(user("trader").roles("TRADER")))
            .andExpect(status().isNoContent());
        mvc.perform(post("/api/risk/__authz_probe").with(user("trader").roles("TRADER")))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/unclassified/__authz_probe").with(user("trader").roles("TRADER")))
            .andExpect(status().isForbidden());
    }

    @Test
    void adminCanReadAndMutateAdminGovernanceAndFailClosedRoutes() throws Exception {
        mvc.perform(get("/api/audit/__authz_probe").with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk());
        mvc.perform(get("/api/admin/__authz_probe").with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk());
        mvc.perform(get("/api/admin/model-governance/__authz_probe").with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk());

        mvc.perform(post("/api/research/__authz_probe").with(user("admin").roles("ADMIN")))
            .andExpect(status().isNoContent());
        mvc.perform(post("/api/risk/__authz_probe").with(user("admin").roles("ADMIN")))
            .andExpect(status().isNoContent());
        mvc.perform(post("/api/unclassified/__authz_probe").with(user("admin").roles("ADMIN")))
            .andExpect(status().isNoContent());
    }

    @Test
    void legacyOperatorCannotEnterV4ModelGovernance() throws Exception {
        mvc.perform(get("/api/admin/__authz_probe").with(user("operator").roles("OPERATOR")))
            .andExpect(status().isOk());
        mvc.perform(get("/api/admin/model-governance/__authz_probe").with(user("operator").roles("OPERATOR")))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/model-governance/__authz_probe").with(user("operator").roles("OPERATOR")))
            .andExpect(status().isForbidden());
    }

    @RestController
    static class AuthorizationProbeController {
        @GetMapping("/api/audit/__authz_probe")
        ResponseEntity<Void> readProbe() {
            return ResponseEntity.ok().build();
        }

        @GetMapping("/api/admin/__authz_probe")
        ResponseEntity<Void> adminReadProbe() {
            return ResponseEntity.ok().build();
        }

        @GetMapping("/api/admin/model-governance/__authz_probe")
        ResponseEntity<Void> governanceReadProbe() {
            return ResponseEntity.ok().build();
        }

        @PostMapping("/api/admin/model-governance/__authz_probe")
        ResponseEntity<Void> governanceWriteProbe() {
            return ResponseEntity.noContent().build();
        }

        @PostMapping("/api/research/__authz_probe")
        ResponseEntity<Void> traderProbe() {
            return ResponseEntity.noContent().build();
        }

        @PostMapping("/api/risk/__authz_probe")
        ResponseEntity<Void> adminProbe() {
            return ResponseEntity.noContent().build();
        }

        @PostMapping("/api/unclassified/__authz_probe")
        ResponseEntity<Void> unclassifiedProbe() {
            return ResponseEntity.noContent().build();
        }
    }
}
