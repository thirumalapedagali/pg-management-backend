package com.pg.expense;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExpenseRepository extends JpaRepository<Expense, Long>, JpaSpecificationExecutor<Expense> {

	Optional<Expense> findByPgIdAndId(Long pgId, Long id);

	@Query("select coalesce(sum(e.amount), 0) from Expense e where e.pgId = :pgId")
	BigDecimal sumAll(@Param("pgId") Long pgId);

	/** Per-day totals in a range, so one query can answer today / month / year / monthly-trend figures. */
	@Query("select e.date, sum(e.amount) from Expense e where e.pgId = :pgId and e.date between :from and :to group by e.date")
	List<Object[]> dailySums(@Param("pgId") Long pgId, @Param("from") LocalDate from, @Param("to") LocalDate to);

	@Query("select coalesce(sum(e.amount), 0) from Expense e where e.pgId = :pgId and e.date between :from and :to")
	BigDecimal sumBetween(@Param("pgId") Long pgId, @Param("from") LocalDate from, @Param("to") LocalDate to);
}
