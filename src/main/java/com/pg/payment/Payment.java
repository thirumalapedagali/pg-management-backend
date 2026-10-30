package com.pg.payment;

import java.math.BigDecimal;
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

/** A rent collection received from a resident. */
@Entity
@Table(name = "payments", indexes = { @Index(columnList = "pg_id, resident_id"), @Index(columnList = "pg_id, date") })
@Getter
@Setter
public class Payment extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "resident_id", nullable = false)
	private Long residentId;

	@Column(nullable = false, precision = 12, scale = 2)
	private BigDecimal amount;

	@Column(nullable = false)
	private LocalDate date;
}
