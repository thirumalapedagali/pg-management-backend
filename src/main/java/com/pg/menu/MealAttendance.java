package com.pg.menu;

import java.time.LocalDate;

import com.pg.tenant.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

/** A resident's answer for a meal on a date: eating it or not, so the kitchen knows how many to cook for. No row = not answered yet. */
@Entity
@Table(name = "meal_attendance",
		uniqueConstraints = @UniqueConstraint(columnNames = { "pg_id", "resident_id", "meal_id", "meal_date" }),
		indexes = @Index(columnList = "pg_id, meal_date"))
@Getter
@Setter
public class MealAttendance extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "resident_id", nullable = false)
	private Long residentId;

	@Column(name = "meal_id", nullable = false)
	private Long mealId;

	@Column(name = "meal_date", nullable = false)
	private LocalDate mealDate;

	/** True = will eat, false = will not. */
	@Column(name = "attending", nullable = false, columnDefinition = "boolean default true")
	private boolean attending = true;
}
