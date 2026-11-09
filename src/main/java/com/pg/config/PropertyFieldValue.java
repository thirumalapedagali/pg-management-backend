package com.pg.config;

import java.math.BigDecimal;

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

/** One property's value for one custom field. Exactly one of the typed columns is used, chosen by the field type. */
@Entity
@Table(name = "property_field_values", uniqueConstraints = @UniqueConstraint(name = "uk_property_field_values", columnNames = {
		"pg_id", "field_id" }))
@Getter
@Setter
public class PropertyFieldValue extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(optional = false)
	@JoinColumn(name = "field_id", nullable = false)
	private PropertyFieldDefinition field;

	@Column(length = 2000)
	private String valueText;

	@Column(precision = 19, scale = 4)
	private BigDecimal valueNumber;

	private Boolean valueBoolean;

	@Column(length = 2000)
	private String valueJson;
}
