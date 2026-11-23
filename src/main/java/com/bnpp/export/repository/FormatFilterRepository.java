package com.bnpp.export.repository;

import com.bnpp.export.entity.FormatFilter;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FormatFilterRepository extends JpaRepository<FormatFilter, Long> {

    List<FormatFilter> findByTemplateIdOrderByPositionAsc(Long templateId);
}
