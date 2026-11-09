package com.pg.config;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AmenityRepository extends JpaRepository<Amenity, Long> {

	List<Amenity> findAllByOrderByCategorySortOrderAscNameAsc();

	List<Amenity> findByActiveTrueOrderByCategorySortOrderAscNameAsc();

	Optional<Amenity> findByCode(String code);
}
