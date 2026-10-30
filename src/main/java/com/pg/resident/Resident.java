package com.pg.resident;

import java.time.LocalDate;

import com.pg.tenant.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "residents", indexes = @Index(columnList = "pg_id, room_id"))
@Getter
@Setter
public class Resident extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 120)
	private String name;

	@Column(length = 20)
	private String phone;

	@Column(name = "room_id", nullable = false)
	private Long roomId;

	@Column(nullable = false)
	private LocalDate joinedOn;
}
