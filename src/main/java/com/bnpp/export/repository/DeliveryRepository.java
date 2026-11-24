package com.bnpp.export.repository;

import com.bnpp.export.entity.DeliveryRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface DeliveryRepository extends JpaRepository<DeliveryRecord, Long> {

    Optional<DeliveryRecord> findFirstBySubscriberIdOrderByCreatedAtDesc(Long subscriberId);

    /** The most recent delivery of every subscriber, in one query (the Subscribers page shows each one's last file). */
    @Query("""
            select d from DeliveryRecord d
             where d.createdAt = (select max(e.createdAt) from DeliveryRecord e where e.subscriberId = d.subscriberId)
            """)
    List<DeliveryRecord> findLatestOfEverySubscriber();

    Optional<DeliveryRecord> findFirstByFilePathOrderByCreatedAtDesc(String filePath);

    boolean existsBySubscriberId(Long subscriberId);
}
