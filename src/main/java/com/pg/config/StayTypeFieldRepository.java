package com.pg.config;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StayTypeFieldRepository extends JpaRepository<StayTypeField, Long> {

	List<StayTypeField> findByStayTypeCodeAndActiveTrueOrderBySortOrderAsc(String stayTypeCode);

	Optional<StayTypeField> findByStayTypeCodeAndFieldId(String stayTypeCode, Long fieldId);
}
