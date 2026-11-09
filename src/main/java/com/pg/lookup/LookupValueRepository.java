package com.pg.lookup;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LookupValueRepository extends JpaRepository<LookupValue, Long> {

	List<LookupValue> findByCategoryAndActiveTrueOrderBySortOrderAscLabelAsc(String category);

	List<LookupValue> findAllByOrderByCategoryAscSortOrderAscLabelAsc();

	Optional<LookupValue> findByCategoryAndCode(String category, String code);

	long countByCategory(String category);
}
