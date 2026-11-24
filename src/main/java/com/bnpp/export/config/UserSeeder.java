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
            // Databases seeded before the roles were renamed: give the demo users their new names.
            rename("maker", "List operator", "List Manager");
            rename("approver", "Export approver", "List Owner");
            return;
        }
        save("maker", "Regliss-maker", "List Manager", "MAKER");
        save("approver", "Regliss-approver", "List Owner", "APPROVER");
    }

    private void rename(String username, String oldName, String newName) {
        appUserRepository.findByUsername(username)
                .filter(user -> oldName.equals(user.getDisplayName()))
                .ifPresent(user -> {
                    user.setDisplayName(newName);
                    appUserRepository.save(user);
                });
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
