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
@Table(name = "room_sharing_options", indexes = @Index(columnList = "pg_id, room_id"))
@Getter
@Setter
public class RoomSharingOption extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "room_id", nullable = false)
	private Long roomId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private SharingType sharingType;

	@Column(nullable = false)
	private int capacity;

	@Column(nullable = false, precision = 12, scale = 2)
	private BigDecimal rentPerBed;

	@Column(precision = 12, scale = 2)
	private BigDecimal depositPerBed = BigDecimal.ZERO;

	@Column(nullable = false)
	private boolean active = true;
}
