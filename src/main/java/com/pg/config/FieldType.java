package com.pg.config;

/** How a custom property field is entered, validated and stored (see property_field_values). */
public enum FieldType {
	TEXT, NUMBER, DECIMAL, BOOLEAN, DATE, TIME, DROPDOWN, MULTI_SELECT, CHECKBOX, RADIO, IMAGE, DOCUMENT;

	/** Types whose value must come from the field's configured options. */
	public boolean hasOptions() {
		return this == DROPDOWN || this == MULTI_SELECT || this == CHECKBOX || this == RADIO;
	}

	/** Types that hold several option values (stored as a JSON array). */
	public boolean isMulti() {
		return this == MULTI_SELECT || this == CHECKBOX;
	}
}
