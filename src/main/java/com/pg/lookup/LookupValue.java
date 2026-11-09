package com.pg.lookup;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One option of a configurable dropdown, shared by every PG (so not tenant scoped).
 * {@code category} groups the options (STAY_TYPE, GENDER_POLICY...), {@code code} is what gets stored on other
 * records, {@code label} is what the user sees and {@code extra} carries a per-option default
 * (the unit label of a stay type, e.g. Flat).
 */
@Entity
@Table(name = "lookup_values", uniqueConstraints = @UniqueConstraint(columnNames = { "category", "code" }))
@Getter
@Setter
@NoArgsConstructor
public class LookupValue {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 40)
	private String category;

	@Column(nullable = false, length = 40)
	private String code;

	@Column(nullable = false, length = 80)
	private String label;

	@Column(length = 80)
	private String extra;

	@Column(name = "sort_order", nullable = false)
	private int sortOrder;

	@Column(nullable = false)
	private boolean active = true;

	public LookupValue(String category, String code, String label, String extra, int sortOrder) {
		this.category = category;
		this.code = code;
		this.label = label;
		this.extra = extra;
		this.sortOrder = sortOrder;
	}
}
