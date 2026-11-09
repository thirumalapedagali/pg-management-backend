package com.pg.config;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** A custom field a property type can ask for. The field itself is data, so a new one needs no code change. */
@Entity
@Table(name = "property_field_definitions")
@Getter
@Setter
public class PropertyFieldDefinition extends ConfigTimestamps {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 40, unique = true)
	private String code;

	@Column(nullable = false, length = 120)
	private String label;

	@Column(length = 255)
	private String description;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private FieldType fieldType;

	@Column(length = 60)
	private String category;

	@Column(length = 160)
	private String placeholder;

	@Column(length = 500)
	private String defaultValue;

	/** JSON array of {"value","label"}; only for DROPDOWN, MULTI_SELECT, CHECKBOX and RADIO. */
	@Column(length = 4000)
	private String options;

	/** JSON object: minLength, maxLength, pattern (TEXT); min, max (NUMBER, DECIMAL). */
	@Column(length = 2000)
	private String validationRules;

	@Column(nullable = false)
	private boolean required;

	@Column(nullable = false)
	private boolean active = true;

	@Column(nullable = false)
	private int sortOrder;
}
