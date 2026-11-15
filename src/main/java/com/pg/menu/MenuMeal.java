package com.pg.menu;

import java.time.LocalTime;

import com.pg.tenant.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** One meal of the property's weekly menu. The menu repeats every week: a meal belongs to a weekday, not a date. */
@Entity
@Table(name = "menu_meals", indexes = @Index(columnList = "pg_id, day_of_week"))
@Getter
@Setter
public class MenuMeal extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** ISO weekday: 1 = Monday ... 7 = Sunday. */
	@Column(name = "day_of_week", nullable = false)
	private int dayOfWeek;

	@Column(nullable = false, length = 40)
	private String name;

	@Column(name = "start_time", nullable = false)
	private LocalTime startTime;

	@Column(name = "end_time", nullable = false)
	private LocalTime endTime;

	/** Food items, one per line. */
	@Column(nullable = false, length = 1500)
	private String items;
}
