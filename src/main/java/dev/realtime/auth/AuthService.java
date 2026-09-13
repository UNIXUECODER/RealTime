package dev.realtime.auth;

import java.time.Instant;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import dev.realtime.tenancy.Tenant;
import dev.realtime.tenancy.TenantRepository;
import dev.realtime.tenancy.User;
import dev.realtime.tenancy.UserRepository;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
public class AuthService {

    /**
     * A syntactically valid (60-char) bcrypt hash that doesn't correspond to any real
     * password. Used only to give {@code login} a comparison to run when the email
     * isn't found — see the timing-leak note below.
     */
    private static final String DUMMY_PASSWORD_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final TransactionTemplate transactionTemplate;

    public AuthService(
            TenantRepository tenantRepository,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            PlatformTransactionManager transactionManager) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        // Built from PlatformTransactionManager directly rather than relying on
        // @Transactional: this method runs inside Mono.fromCallable, called from within
        // this same class — a self-invocation that Spring's AOP proxy wouldn't actually
        // intercept, silently making @Transactional a no-op here. TransactionTemplate
        // manages the transaction programmatically instead, sidestepping proxying
        // entirely.
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Creates a brand-new tenant and its first user (role "owner") in one call — there's
     * no separate "join an existing tenant" flow yet.
     *
     * <p>Tenant + user creation run in one transaction: without it, a user-creation
     * failure after the tenant save already committed would leave an orphaned tenant
     * row with no user able to access it.
     */
    public Mono<String> signup(String tenantName, String email, String rawPassword) {
        return Mono.fromCallable(() -> transactionTemplate.execute(status -> {
                    if (userRepository.findByEmail(email).isPresent()) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already registered");
                    }

                    Tenant tenant = tenantRepository.save(Tenant.builder()
                            .name(tenantName)
                            .plan("free")
                            .createdAt(Instant.now())
                            .build());

                    User user;
                    try {
                        user = userRepository.save(User.builder()
                                .tenantId(tenant.getId())
                                .email(email)
                                .passwordHash(passwordEncoder.encode(rawPassword))
                                .role("owner")
                                .createdAt(Instant.now())
                                .build());
                    } catch (DataIntegrityViolationException e) {
                        // The findByEmail check above has a race window — a concurrent
                        // signup for the same email can slip through it. The database's
                        // own unique constraint is the real guarantee; this turns its
                        // violation into the same clean 409 rather than an unhandled 500.
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already registered");
                    }

                    return jwtService.issue(user.getId(), tenant.getId());
                }))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Deliberately runs a password comparison even when the email isn't found (against
     * a fixed dummy hash, never a real one) — otherwise, an unknown email returns
     * immediately while a known one waits for BCrypt's deliberately-slow comparison, and
     * that timing difference is enough to let an attacker enumerate registered emails
     * without ever seeing a password.
     */
    public Mono<String> login(String email, String rawPassword) {
        return Mono.fromCallable(() -> userRepository.findByEmail(email))
                .subscribeOn(Schedulers.boundedElastic())
                .<User>handle((maybeUser, sink) -> {
                    User user = maybeUser.orElse(null);
                    String hashToCheck = (user != null) ? user.getPasswordHash() : DUMMY_PASSWORD_HASH;
                    boolean matches = passwordEncoder.matches(rawPassword, hashToCheck);
                    if (user != null && matches) {
                        sink.next(user);
                    }
                })
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials")))
                .map(user -> jwtService.issue(user.getId(), user.getTenantId()));
    }
}

