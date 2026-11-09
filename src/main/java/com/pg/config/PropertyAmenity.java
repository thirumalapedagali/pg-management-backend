package com.pg.config;

import com.pg.tenant.BaseEntity;

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

/** An amenity a property offers as a whole (room-level selections live in {@link RoomAmenity}). */
@Entity
@Table(name = "property_amenities", uniqueConstraints = @UniqueConstraint(name = "uk_property_amenities", columnNames = {
		"pg_id", "amenity_id" }))
@Getter
@Setter
public class PropertyAmenity extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(optional = false)
	@JoinColumn(name = "amenity_id", nullable = false)
	private Amenity amenity;

	@Column(nullable = false)
	private boolean enabled = true;

	@Column(length = 500)
	private String notes;
}
