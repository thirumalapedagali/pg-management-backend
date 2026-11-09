package com.pg.config;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PropertyAmenityRepository extends JpaRepository<PropertyAmenity, Long> {

	List<PropertyAmenity> findByPgId(Long pgId);
}
