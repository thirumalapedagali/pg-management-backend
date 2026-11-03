package com.pg.room;

import java.math.BigDecimal;

import com.pg.tenant.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "rooms", uniqueConstraints = @UniqueConstraint(columnNames = { "pg_id", "number" }))
@Getter
@Setter
public class Room extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 30)
	private String number;

	@Column(length = 100)
	private String roomName;

	@Column(name = "building_name", length = 50)
	private String buildingName;

	@Column(name = "floor_name", length = 50)
	private String floorName;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private RoomType roomType = RoomType.STANDARD;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private AcType acType = AcType.NON_AC;

	@Column(nullable = false)
	private boolean attachedBathroom = false;

	@Column(nullable = false)
	private boolean balcony = false;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private GenderPolicy genderPolicy = GenderPolicy.UNISEX;

	@Column(nullable = false)
	private int capacity;

	@Column(nullable = false, precision = 12, scale = 2)
	private BigDecimal rent;

	@Column(name = "daily_rent", precision = 12, scale = 2)
	private BigDecimal dailyRent;
}
