package com.pg.config;

/** Where an amenity can be selected: on the whole property, on a single room, or both. */
public enum AmenityLevel {
	PROPERTY, ROOM, BOTH;

	public boolean appliesTo(AmenityLevel level) {
		return this == BOTH || this == level;
	}
}
