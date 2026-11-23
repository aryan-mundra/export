package com.bnpp.export.repository;

import com.bnpp.export.entity.FormatTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FormatTemplateRepository extends JpaRepository<FormatTemplate, Long> {

    List<FormatTemplate> findAllByOrderByNameAscVersionNumberDesc();

    /** The approved version in use of a template. */
    Optional<FormatTemplate> findFirstByFamilyIdAndActiveTrueOrderByVersionNumberDesc(Long familyId);

    List<FormatTemplate> findByFamilyIdAndActiveTrue(Long familyId);

    Optional<FormatTemplate> findFirstByFamilyIdOrderByVersionNumberDesc(Long familyId);

    /** The newest version of the template with this name, if any. */
    Optional<FormatTemplate> findFirstByNameIgnoreCaseOrderByVersionNumberDesc(String name);
}
