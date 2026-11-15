package com.pg.menu;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MealAttendanceRepository extends JpaRepository<MealAttendance, Long> {

	Optional<MealAttendance> findByPgIdAndResidentIdAndMealIdAndMealDate(Long pgId, Long residentId, Long mealId,
			LocalDate mealDate);

	List<MealAttendance> findByPgIdAndResidentIdAndMealDateIn(Long pgId, Long residentId, Collection<LocalDate> dates);

	void deleteByPgIdAndMealId(Long pgId, Long mealId);

	/** Rows of {mealId, attending (true / false), number of residents} for one date. */
	@Query("select a.mealId, a.attending, count(a) from MealAttendance a where a.pgId = :pgId and a.mealDate = :date group by a.mealId, a.attending")
	List<Object[]> countByMeal(@Param("pgId") Long pgId, @Param("date") LocalDate date);
}
