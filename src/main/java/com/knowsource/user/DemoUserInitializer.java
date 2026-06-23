package com.knowsource.user;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class DemoUserInitializer implements ApplicationRunner {

    public static final String DEMO_USERNAME = "demo";
    private static final String DEMO_PASSWORD_HASH = "{noop}demo";
    private static final String DEMO_EMAIL = "demo@knowsource.local";
    private static final String DEMO_GLOBAL_ROLE = "ADMIN";

    private final JdbcClient jdbcClient;

    public DemoUserInitializer(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
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
                    .param("passwordHash", DEMO_PASSWORD_HASH)
                    .param("email", DEMO_EMAIL)
                    .param("globalRole", DEMO_GLOBAL_ROLE)
                    .update();
        }
    }
}
