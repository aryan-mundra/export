package com.bnpp.export.repository;

import com.bnpp.export.model.DataSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface DataSnapshotRepository extends JpaRepository<DataSnapshot, Long> {

    List<DataSnapshot> findAllByOrderByIdDesc();

    List<DataSnapshot> findByFamilyIdOrderByIdDesc(Long familyId);

    Optional<DataSnapshot> findFirstByFamilyIdAndListCodeAndTemplateIdAndFingerprint(Long familyId, String listCode,
                                                                                     Long templateId, String fingerprint);

    Optional<DataSnapshot> findFirstByFamilyIdAndListCodeOrderByIdDesc(Long familyId, String listCode);

    @Query("select max(s.minor) from DataSnapshot s where s.familyId = ?1 and s.listCode = ?2 and s.major = ?3")
    Integer maxMinor(Long familyId, String listCode, int major);
}
