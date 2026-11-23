package com.bnpp.export.repository;

import com.bnpp.export.model.DataSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface DataSnapshotRepository extends JpaRepository<DataSnapshot, Long> {

    List<DataSnapshot> findAllByOrderByIdDesc();

    List<DataSnapshot> findByFamilyIdOrderByIdDesc(Long familyId);

    org.springframework.data.domain.Page<DataSnapshot> findByFamilyIdAndListCodeOrderByIdDesc(Long familyId, String listCode,
                                                                                            org.springframework.data.domain.Pageable page);

    List<DataSnapshot> findTop200ByOrderByIdDesc();

    List<DataSnapshot> findTop200ByFamilyIdOrderByIdDesc(Long familyId);

    Optional<DataSnapshot> findFirstByFamilyIdAndListCodeAndTemplateIdAndFingerprint(Long familyId, String listCode,
                                                                                     Long templateId, String fingerprint);

    Optional<DataSnapshot> findFirstByFamilyIdAndListCodeOrderByIdDesc(Long familyId, String listCode);

    @Query("select max(s.minor) from DataSnapshot s where s.familyId = ?1 and s.listCode = ?2 and s.major = ?3")
    Integer maxMinor(Long familyId, String listCode, int major);

    boolean existsByTableName(String tableName);
}
