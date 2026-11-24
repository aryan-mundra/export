package com.bnpp.export.repository;

import com.bnpp.export.entity.FormatField;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FormatFieldRepository extends JpaRepository<FormatField, Long> {

    List<FormatField> findByTemplateIdOrderByPositionAsc(Long templateId);

    /** The columns of every list version at once, for building the whole list in one query. */
    List<FormatField> findAllByOrderByTemplateIdAscPositionAsc();
}
