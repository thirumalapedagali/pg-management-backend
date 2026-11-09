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

/** An amenity a single room has. Kept apart from {@link PropertyAmenity}. Removed together with the room. */
@Entity
@Table(name = "room_amenities", uniqueConstraints = @UniqueConstraint(name = "uk_room_amenities", columnNames = {
		"room_id", "amenity_id" }))
@Getter
@Setter
public class RoomAmenity extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private Long roomId;

	@ManyToOne(optional = false)
	@JoinColumn(name = "amenity_id", nullable = false)
	private Amenity amenity;

	@Column(nullable = false)
	private boolean enabled = true;
}
