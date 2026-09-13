package dev.realtime.auth;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.web.server.ResponseStatusException;

import dev.realtime.tenancy.Tenant;
import dev.realtime.tenancy.TenantRepository;
import dev.realtime.tenancy.User;
import dev.realtime.tenancy.UserRepository;

import reactor.test.StepVerifier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Validates M6a fixes to {@link AuthService}: transactional signup boundaries,
 * TOCTOU race handling on duplicate emails, login timing-attack mitigation, and
 * the Reactor {@code handle()} fix that prevents NPE on bad credentials.
 *
 * <p>Uses a mock {@link PlatformTransactionManager} that lets
 * {@code TransactionTemplate.execute()} run the callback normally — the test
 * doesn't verify database-level rollback (that's Postgres/JPA's job), only that
 * the application-level logic inside the callback behaves correctly.
 */
class AuthServiceTest {

    private TenantRepository tenantRepository;
    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private JwtService jwtService;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        tenantRepository = mock(TenantRepository.class);
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        jwtService = mock(JwtService.class);

        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        authService = new AuthService(
                tenantRepository, userRepository, passwordEncoder, jwtService, txManager);
    }

    // ── Signup ────────────────────────────────────────────────────────────────

    @Test
    void signupHappyPathSavesTenantAndUserAndReturnsJwt() {
        when(userRepository.findByEmail("new@test.com")).thenReturn(Optional.empty());
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(inv -> {
            Tenant t = inv.getArgument(0);
            // Simulate JPA generating an ID on save.
            return Tenant.builder().id(1L).name(t.getName()).plan(t.getPlan()).createdAt(t.getCreatedAt()).build();
        });
        when(passwordEncoder.encode("pass123")).thenReturn("$2a$10$encoded");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            return User.builder().id(10L).tenantId(u.getTenantId()).email(u.getEmail())
                    .passwordHash(u.getPasswordHash()).role(u.getRole()).createdAt(u.getCreatedAt()).build();
        });
        when(jwtService.issue(10L, 1L)).thenReturn("jwt-token");

        StepVerifier.create(authService.signup("Acme", "new@test.com", "pass123"))
                .expectNext("jwt-token")
                .verifyComplete();

        verify(tenantRepository).save(any(Tenant.class));
        verify(userRepository).save(any(User.class));
    }

    @Test
    void signupRejects409WhenEmailAlreadyExists() {
        User existing = User.builder().id(1L).tenantId(1L).email("taken@test.com")
                .passwordHash("hash").role("owner").createdAt(Instant.now()).build();
        when(userRepository.findByEmail("taken@test.com")).thenReturn(Optional.of(existing));

        StepVerifier.create(authService.signup("Acme", "taken@test.com", "pass"))
                .expectErrorSatisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assert rse.getStatusCode().value() == HttpStatus.CONFLICT.value();
                })
                .verify();
    }

    @Test
    void signupCatchesToctouRaceAndReturns409() {
        // findByEmail returns empty (passes the fast check), but userRepository.save
        // throws because a concurrent signup committed the same email first.
        when(userRepository.findByEmail("race@test.com")).thenReturn(Optional.empty());
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(inv -> {
            Tenant t = inv.getArgument(0);
            return Tenant.builder().id(1L).name(t.getName()).plan(t.getPlan()).createdAt(t.getCreatedAt()).build();
        });
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$encoded");
        when(userRepository.save(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("unique constraint"));

        StepVerifier.create(authService.signup("Acme", "race@test.com", "pass"))
                .expectErrorSatisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assert rse.getStatusCode().value() == HttpStatus.CONFLICT.value();
                    assert rse.getReason().contains("Email already registered");
                })
                .verify();
    }

    // ── Login ─────────────────────────────────────────────────────────────────

    @Test
    void loginHappyPathReturnsJwt() {
        User user = User.builder().id(5L).tenantId(2L).email("user@test.com")
                .passwordHash("$2a$10$real").role("owner").createdAt(Instant.now()).build();
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("correct", "$2a$10$real")).thenReturn(true);
        when(jwtService.issue(5L, 2L)).thenReturn("login-jwt");

        StepVerifier.create(authService.login("user@test.com", "correct"))
                .expectNext("login-jwt")
                .verifyComplete();
    }

    @Test
    void loginWithWrongPasswordReturns401NotNpe() {
        // This is the Reactor handle() fix — the old code used .map() that returned
        // null on mismatch, causing an NPE. The new code uses handle() which cleanly
        // completes empty, letting switchIfEmpty return 401.
        User user = User.builder().id(5L).tenantId(2L).email("user@test.com")
                .passwordHash("$2a$10$real").role("owner").createdAt(Instant.now()).build();
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "$2a$10$real")).thenReturn(false);

        StepVerifier.create(authService.login("user@test.com", "wrong"))
                .expectErrorSatisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assert rse.getStatusCode().value() == HttpStatus.UNAUTHORIZED.value();
                })
                .verify();
    }

    @Test
    void loginWithUnknownEmailReturns401NotNpe() {
        // Same fix — unknown email should still run passwordEncoder.matches() (timing
        // attack mitigation) and return 401, not blow up with an NPE.
        when(userRepository.findByEmail("nobody@test.com")).thenReturn(Optional.empty());
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

        StepVerifier.create(authService.login("nobody@test.com", "whatever"))
                .expectErrorSatisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assert rse.getStatusCode().value() == HttpStatus.UNAUTHORIZED.value();
                })
                .verify();
    }

    @Test
    void loginAlwaysRunsBcryptEvenForUnknownEmail() {
        // Timing attack mitigation: the old code skipped BCrypt entirely when the email
        // wasn't found, creating a measurable timing difference. The fix always runs a
        // comparison, against a dummy hash when there's no real user.
        when(userRepository.findByEmail("nobody@test.com")).thenReturn(Optional.empty());
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

        StepVerifier.create(authService.login("nobody@test.com", "anything"))
                .expectError(ResponseStatusException.class)
                .verify();

        // The key assertion: passwordEncoder.matches was called even though no user was found.
        verify(passwordEncoder).matches(anyString(), anyString());
    }
}
