package com.knowsource.user;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Seeds a demo user so a fresh deployment can be explored locally.
 *
 * <p>Security notes (P0 fix D-1):
 * <ul>
 *   <li>Disabled unless explicitly enabled. Production deployments are safe by default; set
 *       {@code KNOWSOURCE_DEMO_USER_ENABLED=true} only for local/demo environments.</li>
 *   <li>The password is stored as a BCrypt hash, never plaintext {@code {noop}}.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "knowsource.demo-user.enabled", havingValue = "true")
public class DemoUserInitializer implements ApplicationRunner {

    public static final String DEMO_USERNAME = "demo";
    public static final String DEMO_PASSWORD = "demo";
    private static final String DEMO_EMAIL = "demo@knowsource.local";
    private static final String DEMO_GLOBAL_ROLE = "ADMIN";

    private final JdbcClient jdbcClient;
    private final PasswordEncoder passwordEncoder;

    public DemoUserInitializer(JdbcClient jdbcClient, PasswordEncoder passwordEncoder) {
        this.jdbcClient = jdbcClient;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        Long userCount = jdbcClient.sql("SELECT COUNT(*) FROM users")
                .query(Long.class)
                .single();

        if (userCount == 0) {
            jdbcClient.sql("""
                    INSERT INTO users (username, password_hash, email, global_role)
                    VALUES (:username, :passwordHash, :email, :globalRole)
                    """)
                    .param("username", DEMO_USERNAME)
                    .param("passwordHash", passwordEncoder.encode(DEMO_PASSWORD))
                    .param("email", DEMO_EMAIL)
                    .param("globalRole", DEMO_GLOBAL_ROLE)
                    .update();
        }
    }
}
