package com.knowsource.user;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class DemoUserService {

    private final JdbcClient jdbcClient;

    public DemoUserService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public long currentUserId() {
        return jdbcClient.sql("SELECT id FROM users WHERE username = :username")
                .param("username", DemoUserInitializer.DEMO_USERNAME)
                .query(Long.class)
                .single();
    }
}
