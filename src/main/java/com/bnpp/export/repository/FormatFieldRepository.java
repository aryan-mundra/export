package com.bnpp.export.repository;

import com.bnpp.export.model.FormatField;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FormatFieldRepository extends JpaRepository<FormatField, Long> {

    List<FormatField> findByTemplateIdOrderByPositionAsc(Long templateId);
}
