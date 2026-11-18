package com.bnpp.export.repository;

import com.bnpp.export.model.FormatTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FormatTemplateRepository extends JpaRepository<FormatTemplate, Long> {

    List<FormatTemplate> findAllByOrderByFormatTypeAscVersionNumberDesc();

    Optional<FormatTemplate> findFirstByFormatTypeAndActiveTrueOrderByVersionNumberDesc(String formatType);

    List<FormatTemplate> findByFormatTypeAndActiveTrue(String formatType);

    Optional<FormatTemplate> findFirstByFormatTypeOrderByVersionNumberDesc(String formatType);
}
