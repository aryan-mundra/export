package com.bnpp.export.repository;

import com.bnpp.export.model.CertifiedVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CertifiedVersionRepository extends JpaRepository<CertifiedVersion, Long> {

    Optional<CertifiedVersion> findByVersionId(Long versionId);

    List<CertifiedVersion> findAllByOrderByListNameAscVersionIdAsc();

    List<CertifiedVersion> findAllByOrderByVersionIdAsc();
}
