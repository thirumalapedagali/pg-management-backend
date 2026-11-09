package com.pg.config;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AmenityCategoryRepository extends JpaRepository<AmenityCategory, Long> {

	List<AmenityCategory> findAllByOrderBySortOrderAscNameAsc();

	List<AmenityCategory> findByActiveTrueOrderBySortOrderAscNameAsc();

	Optional<AmenityCategory> findByCode(String code);
}
