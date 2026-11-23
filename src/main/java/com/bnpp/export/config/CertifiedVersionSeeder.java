package com.bnpp.export.config;

import com.bnpp.export.entity.CertifiedVersion;
import com.bnpp.export.repository.CertifiedVersionRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
@ConditionalOnProperty(name = "export.mock.enabled", havingValue = "true")
public class CertifiedVersionSeeder implements ApplicationListener<ApplicationReadyEvent> {

    private final CertifiedVersionRepository repository;

    public CertifiedVersionSeeder(CertifiedVersionRepository repository) {
        this.repository = repository;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (repository.count() > 0) {
            return;
        }
        save(42L, "SANCTIONS", "Sanctions", LocalDate.of(2026, 11, 18), true);
        save(43L, "WORLDCHECK", "World-Check", LocalDate.of(2026, 11, 1), true);
        save(44L, "PFA", "PFA", LocalDate.of(2026, 10, 15), true);
        save(77L, "SANCTIONS", "Sanctions", LocalDate.of(2026, 11, 18), false);
    }

    private void save(Long versionId, String listCode, String listName, LocalDate contentAsOf, boolean operatorVisible) {
        CertifiedVersion version = new CertifiedVersion();
        version.setVersionId(versionId);
        version.setListCode(listCode);
        version.setListName(listName);
        version.setContentAsOf(contentAsOf);
        version.setStatus("CERTIFIED");
        version.setOperatorVisible(operatorVisible);
        repository.save(version);
    }
}
