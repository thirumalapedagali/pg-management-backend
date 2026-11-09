package com.pg.config;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PropertyFieldDefinitionRepository extends JpaRepository<PropertyFieldDefinition, Long> {

	List<PropertyFieldDefinition> findAllByOrderBySortOrderAscLabelAsc();

	Optional<PropertyFieldDefinition> findByCode(String code);
}
