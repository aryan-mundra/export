package com.bnpp.export.config;

import com.bnpp.export.entity.AppUser;
import com.bnpp.export.repository.AppUserRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class UserSeeder implements ApplicationListener<ApplicationReadyEvent> {

    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;

    public UserSeeder(AppUserRepository appUserRepository, PasswordEncoder passwordEncoder) {
        this.appUserRepository = appUserRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (appUserRepository.count() > 0) {
            return;
        }
        save("maker", "Regliss-maker", "List operator", "MAKER");
        save("approver", "Regliss-approver", "Export approver", "APPROVER");
    }

    private void save(String username, String password, String displayName, String role) {
        AppUser user = new AppUser();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setDisplayName(displayName);
        user.setRole(role);
        appUserRepository.save(user);
    }
}
