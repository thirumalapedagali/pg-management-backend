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
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "pg_beds", indexes = {
		@Index(columnList = "pg_id, section_id"),
		@Index(columnList = "pg_id, room_id"),
		@Index(columnList = "pg_id, resident_id")
})
@Getter
@Setter
public class Bed extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "room_id", nullable = false)
	private Long roomId;

	@Column(name = "section_id", nullable = false)
	private Long sectionId;

	@Column(nullable = false, length = 50)
	private String bedNumber;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private BedType bedType = BedType.SINGLE;

	@Column(precision = 12, scale = 2)
	private BigDecimal customRentOverride;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private BedStatus status = BedStatus.VACANT;

	@Column(name = "resident_id")
	private Long residentId;
}
