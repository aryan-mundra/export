package com.bnpp.export.repository;

import com.bnpp.export.entity.SubscriberProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SubscriberRepository extends JpaRepository<SubscriberProfile, Long> {

    List<SubscriberProfile> findAllByOrderByIdAsc();

    List<SubscriberProfile> findByActiveTrueOrderByIdAsc();

    long countByActiveTrue();

    boolean existsByNameIgnoreCase(String name);

    Optional<SubscriberProfile> findByNameIgnoreCase(String name);

    boolean existsByCftEndpointIgnoreCaseAndEndpointStatus(String cftEndpoint, String endpointStatus);

    List<SubscriberProfile> findByFormatTypeIgnoreCaseAndActiveTrueOrderByNameAsc(String formatType);

    List<SubscriberProfile> findByTemplateFamilyIdOrderByNameAsc(Long templateFamilyId);
}
