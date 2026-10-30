package com.pg.expense;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ExpenseCategoryRepository extends JpaRepository<ExpenseCategory, Long> {

	List<ExpenseCategory> findByPgIdOrderByName(Long pgId);

	Optional<ExpenseCategory> findByPgIdAndId(Long pgId, Long id);

	boolean existsByPgIdAndNameIgnoreCase(Long pgId, String name);
}
