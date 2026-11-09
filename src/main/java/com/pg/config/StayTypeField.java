package com.pg.config;

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

/** Asks properties of one type for a custom field. */
@Entity
@Table(name = "stay_type_fields", uniqueConstraints = @UniqueConstraint(name = "uk_stay_type_fields", columnNames = {
		"stay_type_code", "field_id" }))
@Getter
@Setter
public class StayTypeField {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 40)
	private String stayTypeCode;

	@ManyToOne(optional = false)
	@JoinColumn(name = "field_id", nullable = false)
	private PropertyFieldDefinition field;

	@Column(nullable = false)
	private boolean required;

	@Column(nullable = false)
	private int sortOrder;

	@Column(nullable = false)
	private boolean active = true;
}
