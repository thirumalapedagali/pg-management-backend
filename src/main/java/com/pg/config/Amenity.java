package com.pg.config;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** An amenity any property type can offer (Wi-Fi, CCTV, Gym...). Global master data, reusable across types. */
@Entity
@Table(name = "amenities")
@Getter
@Setter
public class Amenity extends ConfigTimestamps {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(optional = false)
	@JoinColumn(name = "category_id", nullable = false)
	private AmenityCategory category;

	@Column(nullable = false, length = 40, unique = true)
	private String code;

	@Column(nullable = false, length = 100)
	private String name;

	@Column(length = 255)
	private String description;

	@Column(length = 60)
	private String icon;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private AmenityLevel applicableLevel = AmenityLevel.BOTH;

	@Column(nullable = false)
	private boolean active = true;
}
