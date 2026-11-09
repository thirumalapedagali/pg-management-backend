package com.pg.config;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PropertyFieldValueRepository extends JpaRepository<PropertyFieldValue, Long> {

	List<PropertyFieldValue> findByPgId(Long pgId);
}
