package com.pg.menu;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MenuMealRepository extends JpaRepository<MenuMeal, Long> {

	List<MenuMeal> findByPgIdOrderByDayOfWeekAscStartTimeAsc(Long pgId);

	List<MenuMeal> findByPgIdAndDayOfWeekOrderByStartTimeAsc(Long pgId, int dayOfWeek);

	/** Several weekdays in one trip to the database; within a day the meals are in time order. */
	List<MenuMeal> findByPgIdAndDayOfWeekInOrderByStartTimeAsc(Long pgId, Collection<Integer> daysOfWeek);

	Optional<MenuMeal> findByPgIdAndId(Long pgId, Long id);
}
