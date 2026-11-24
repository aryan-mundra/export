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

    /** Every subscriber that has chosen a list, by name: who uses each list, in one query. */
    List<SubscriberProfile> findByTemplateFamilyIdIsNotNullOrderByNameAsc();

    List<SubscriberProfile> findByTemplateFamilyIdOrderByNameAsc(Long templateFamilyId);
}
