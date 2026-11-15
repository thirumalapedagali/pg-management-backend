package com.pg.menu;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MenuMealRepository extends JpaRepository<MenuMeal, Long> {

	List<MenuMeal> findByPgIdOrderByDayOfWeekAscStartTimeAsc(Long pgId);

	List<MenuMeal> findByPgIdAndDayOfWeekOrderByStartTimeAsc(Long pgId, int dayOfWeek);

	Optional<MenuMeal> findByPgIdAndId(Long pgId, Long id);
}
