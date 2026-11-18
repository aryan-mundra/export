package com.bnpp.export.security;

import com.bnpp.export.repository.AppUserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class ExportUserDetailsService implements UserDetailsService {

    private final AppUserRepository appUserRepository;

    public ExportUserDetailsService(AppUserRepository appUserRepository) {
        this.appUserRepository = appUserRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        return appUserRepository.findByUsername(username == null ? "" : username.trim().toLowerCase())
                .map(user -> new ExportPrincipal(user.getUsername(), user.getPasswordHash(), user.getDisplayName(), user.getRole()))
                .orElseThrow(() -> new UsernameNotFoundException("Unknown username or password"));
    }
}
