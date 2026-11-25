package com.bnpp.export.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/** One value a list keeps for a column of a group table (a list version holds several values per column). */
@Data
@Entity
@Table(name = "ver_export_template_filter")
public class FormatFilter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long templateId;

    @Column(nullable = false)
    private String sourceTable;

    @Column(nullable = false)
    private String sourceColumn;

    /** The value as the database shows it as text (trimmed). */
    @Column(nullable = false, length = 1000)
    private String filterValue;
}
