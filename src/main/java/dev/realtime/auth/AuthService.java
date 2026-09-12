package dev.realtime.auth;

import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import dev.realtime.tenancy.Tenant;
import dev.realtime.tenancy.TenantRepository;
import dev.realtime.tenancy.User;
import dev.realtime.tenancy.UserRepository;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
public class AuthService {

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(
            TenantRepository tenantRepository,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    /** Creates a brand-new tenant and its first user (role "owner") in one call — there's no separate "join an existing tenant" flow yet. */
    public Mono<String> signup(String tenantName, String email, String rawPassword) {
        return Mono.fromCallable(() -> {
                    if (userRepository.findByEmail(email).isPresent()) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already registered");
                    }

                    Tenant tenant = tenantRepository.save(Tenant.builder()
                            .name(tenantName)
                            .plan("free")
                            .createdAt(Instant.now())
                            .build());

                    User user = userRepository.save(User.builder()
                            .tenantId(tenant.getId())
                            .email(email)
                            .passwordHash(passwordEncoder.encode(rawPassword))
                            .role("owner")
                            .createdAt(Instant.now())
                            .build());

                    return jwtService.issue(user.getId(), tenant.getId());
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<String> login(String email, String rawPassword) {
        return Mono.fromCallable(() -> userRepository.findByEmail(email))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(Mono::justOrEmpty)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials")))
                .flatMap(user -> {
                    if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
                        return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));
                    }
                    return Mono.just(jwtService.issue(user.getId(), user.getTenantId()));
                });
    }
}
