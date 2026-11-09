package com.pg.config;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StayTypeAmenityRepository extends JpaRepository<StayTypeAmenity, Long> {

	List<StayTypeAmenity> findByStayTypeCodeAndActiveTrueOrderBySortOrderAsc(String stayTypeCode);

	Optional<StayTypeAmenity> findByStayTypeCodeAndAmenityId(String stayTypeCode, Long amenityId);
}
