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
@Table(name = "room_sections", indexes = @Index(columnList = "pg_id, room_id"))
@Getter
@Setter
public class RoomSection extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "room_id", nullable = false)
	private Long roomId;

	@Column(nullable = false, length = 100)
	private String sectionName;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private SharingType sharingType = SharingType.PRIVATE;

	@Column(nullable = false)
	private int capacity = 1;

	@Column(nullable = false, precision = 12, scale = 2)
	private BigDecimal rentPerBed = BigDecimal.ZERO;

	@Column(precision = 12, scale = 2)
	private BigDecimal depositPerBed = BigDecimal.ZERO;

	@Enumerated(EnumType.STRING)
	@Column(length = 20)
	private AcType acType;

	@Column(nullable = false)
	private boolean attachedBathroom = false;
}
