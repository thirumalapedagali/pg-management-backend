package com.pg.config;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

/**
 * Offers an amenity to a property type. {@code stayTypeCode} is a STAY_TYPE lookup code; it is validated by the API
 * rather than by a foreign key, because lookup_values is unique on (category, code) only.
 */
@Entity
@Table(name = "stay_type_amenities", uniqueConstraints = @UniqueConstraint(name = "uk_stay_type_amenities", columnNames = {
		"stay_type_code", "amenity_id" }))
@Getter
@Setter
public class StayTypeAmenity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 40)
	private String stayTypeCode;

	@ManyToOne(optional = false)
	@JoinColumn(name = "amenity_id", nullable = false)
	private Amenity amenity;

	@Column(nullable = false)
	private boolean defaultSelected;

	@Column(nullable = false)
	private boolean required;

	@Column(nullable = false)
	private int sortOrder;

	@Column(nullable = false)
	private boolean active = true;
}
