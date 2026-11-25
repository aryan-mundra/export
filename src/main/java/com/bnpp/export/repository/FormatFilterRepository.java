package com.bnpp.export.repository;

import com.bnpp.export.entity.FormatFilter;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FormatFilterRepository extends JpaRepository<FormatFilter, Long> {

    List<FormatFilter> findByTemplateIdOrderByIdAsc(Long templateId);

    /** The values of every list version at once, for building the whole list in one query. */
    List<FormatFilter> findAllByOrderByTemplateIdAscIdAsc();
}
