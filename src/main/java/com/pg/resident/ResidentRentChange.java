package com.pg.resident;

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

/**
 * A change to a monthly resident's rent, effective from the start of a billing cycle. The rent a resident joined
 * with stays on the resident; billing uses the latest change whose date is on or before each cycle's start.
 */
@Entity
@Table(name = "resident_rent_changes", indexes = @Index(columnList = "pg_id, resident_id"))
@Getter
@Setter
public class ResidentRentChange extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "resident_id", nullable = false)
	private Long residentId;

	@Column(name = "effective_from", nullable = false)
	private LocalDate effectiveFrom;

	@Column(nullable = false, precision = 12, scale = 2)
	private BigDecimal rent;
}
