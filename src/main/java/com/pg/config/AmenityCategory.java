package com.pg.config;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** A group of amenities (ROOM, FOOD, SECURITY...). Global master data. */
@Entity
@Table(name = "amenity_categories")
@Getter
@Setter
public class AmenityCategory extends ConfigTimestamps {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 40, unique = true)
	private String code;

	@Column(nullable = false, length = 80)
	private String name;

	@Column(length = 255)
	private String description;

	@Column(nullable = false)
	private int sortOrder;

	@Column(nullable = false)
	private boolean active = true;
}
