package com.bnpp.export.repository;

import com.bnpp.export.entity.DeliveryRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeliveryRepository extends JpaRepository<DeliveryRecord, Long> {

    List<DeliveryRecord> findByVersionIdOrderByCreatedAtDesc(Long versionId);

    Optional<DeliveryRecord> findFirstBySubscriberIdOrderByCreatedAtDesc(Long subscriberId);

    Optional<DeliveryRecord> findFirstByFilePathOrderByCreatedAtDesc(String filePath);

    boolean existsBySubscriberId(Long subscriberId);
}
