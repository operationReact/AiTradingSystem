package in.aitradingsystem.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.aitradingsystem.mind.MindCapabilityProgressService;
import in.aitradingsystem.user.CustomerIdentityStore;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.server.ResponseStatusException;

class CustomerMindProgressControllerTest {
    private static final String ISSUER = "https://cognito-idp.ap-south-1.amazonaws.com/ap-south-1_test";
    private final CustomerIdentityStore identities = mock(CustomerIdentityStore.class);
    private final MindCapabilityProgressService service = mock(MindCapabilityProgressService.class);
    private final CustomerMindProgressController controller =
        new CustomerMindProgressController(service, identities);

    @Test
    void progressRequiresLinkedCustomerAndPassesViewerForNoteScoping() {
        assertThatThrownBy(() -> controller.inspect(new UsernamePasswordAuthenticationToken("op", "x")))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
        verifyNoInteractions(service);

        UUID owner = UUID.randomUUID();
        controller.inspect(principal("sub-1", owner));
        verify(service).inspect(owner);
        controller.note("screen.coverage", "recheck-0001",
            new CustomerMindProgressController.NoteRequest("RECHECK_REQUESTED", null), principal("sub-1", owner));
        verify(service).addNote(eq(owner), eq("screen.coverage"),
            eq(MindCapabilityProgressService.EventType.RECHECK_REQUESTED), any(), eq("recheck-0001"));
    }

    @Test
    void noHttpRouteCanWriteAStatus() {
        List<Method> writes = Arrays.stream(CustomerMindProgressController.class.getDeclaredMethods())
            .filter(m -> m.isAnnotationPresent(PostMapping.class) || m.isAnnotationPresent(PutMapping.class)
                || m.isAnnotationPresent(PatchMapping.class) || m.isAnnotationPresent(DeleteMapping.class))
            .toList();
        assertThat(writes).hasSize(1);
        assertThat(writes.getFirst().getAnnotation(PostMapping.class).value())
            .containsExactly("/items/{capabilityId}/notes");
        assertThat(CustomerMindProgressController.NoteRequest.class.getRecordComponents())
            .extracting(java.lang.reflect.RecordComponent::getName).containsExactly("type", "text");
    }

    private JwtAuthenticationToken principal(String subject, UUID owner) {
        var jwt = Jwt.withTokenValue("test-jwt").header("alg", "RS256").issuer(ISSUER).subject(subject)
            .issuedAt(Instant.parse("2026-11-13T00:00:00Z")).expiresAt(Instant.parse("2026-11-14T00:00:00Z"))
            .build();
        when(identities.find("cognito", ISSUER, subject)).thenReturn(Optional.of(
            new CustomerIdentityStore.CustomerAccount(owner, "ACTIVE", "CUSTOMER", "Test", "t@example.invalid")));
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
    }
}
