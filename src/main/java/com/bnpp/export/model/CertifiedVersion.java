package com.bnpp.export.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDate;

@Data
@Entity
@Table(name = "ver_version")
public class CertifiedVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long versionId;

    @Column(nullable = false)
    private String listCode;

    @Column(nullable = false)
    private String listName;

    private LocalDate contentAsOf;

    @Column(nullable = false)
    private String status;

    private boolean operatorVisible = true;
}
