package com.knowsource.security;

import java.util.Collection;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public record CurrentUserPrincipal(CurrentUser currentUser) implements UserDetails {

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return java.util.List.of(() -> "ROLE_" + currentUser.globalRole());
    }

    @Override
    public String getPassword() {
        return "";
    }

    @Override
    public String getUsername() {
        return currentUser.username();
    }
}
