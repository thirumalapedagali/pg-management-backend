package com.pg.config;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/** Request and response shapes of the configuration APIs. */
public final class ConfigDtos {

	private ConfigDtos() {
	}

	static final String KEY = "^[A-Za-z][A-Za-z0-9_]*$";
	static final String KEY_MESSAGE = "Use letters, digits and _ only";

	// ── responses ──────────────────────────────────────────────────────────────────────────────────────────────
	public record PropertyTypeDto(String code, String label, String unitLabel) {
	}

	public record PropertyTypeDetail(String code, String label, String unitLabel, List<TypeAmenityDto> amenities,
			List<TypeFieldDto> fields) {
	}

	public record CategoryDto(Long id, String code, String name, String description, int sortOrder, boolean active) {
	}

	public record AmenityDto(Long id, String code, String name, String description, String icon,
			AmenityLevel applicableLevel, boolean active, String categoryCode, String categoryName) {
	}

	/** An amenity as offered to one property type. */
	public record TypeAmenityDto(AmenityDto amenity, boolean defaultSelected, boolean required, int sortOrder,
			boolean active) {
	}

	public record FieldDto(Long id, String code, String label, String description, FieldType fieldType, String category,
			String placeholder, String defaultValue, JsonNode options, JsonNode validationRules, boolean required,
			boolean active, int sortOrder) {
	}

	/** A field as asked for by one property type. {@code required} is true if the field or the mapping requires it. */
	public record TypeFieldDto(FieldDto field, boolean required, int sortOrder, boolean active) {
	}

	public record AmenityState(AmenityDto amenity, boolean defaultSelected, boolean required, boolean selected,
			String notes) {
	}

	public record FieldState(FieldDto field, boolean required, JsonNode value) {
	}

	public record PropertyConfiguration(Long propertyId, String name, String stayType, String stayTypeLabel,
			List<AmenityState> amenities, List<FieldState> fields) {
	}

	public record RoomAmenities(Long roomId, List<AmenityState> amenities) {
	}

	// ── admin requests ─────────────────────────────────────────────────────────────────────────────────────────
	public record CreateAmenityRequest(
			@NotBlank @Size(max = 40) @Pattern(regexp = KEY, message = KEY_MESSAGE) String code,
			@NotBlank @Size(max = 100) String name, @NotBlank @Size(max = 40) String categoryCode,
			@Size(max = 255) String description, @Size(max = 60) String icon, AmenityLevel applicableLevel) {
	}

	public record CreateFieldRequest(
			@NotBlank @Size(max = 40) @Pattern(regexp = KEY, message = KEY_MESSAGE) String code,
			@NotBlank @Size(max = 120) String label, @Size(max = 255) String description,
			@NotNull FieldType fieldType, @Size(max = 60) String category, @Size(max = 160) String placeholder,
			@Size(max = 500) String defaultValue, JsonNode options, JsonNode validationRules, Boolean required,
			Integer sortOrder) {
	}

	/** Changes to an amenity; omitted fields stay as they are. The code is fixed once created. */
	public record UpdateAmenityRequest(@Size(max = 100) String name, @Size(max = 40) String categoryCode,
			@Size(max = 255) String description, @Size(max = 60) String icon, AmenityLevel applicableLevel,
			Boolean active) {
	}

	/** Changes to a field definition. Code, type and options are fixed because saved values depend on them. */
	public record UpdateFieldRequest(@Size(max = 120) String label, @Size(max = 255) String description,
			@Size(max = 60) String category, @Size(max = 160) String placeholder, @Size(max = 500) String defaultValue,
			Boolean required, Integer sortOrder, Boolean active) {
	}

	/** Creates or updates the link between a property type and an amenity ({@code active=false} retires it). */
	public record MapAmenityRequest(@NotBlank @Size(max = 40) String amenityCode, Boolean defaultSelected,
			Boolean required, Integer sortOrder, Boolean active) {
	}

	public record MapFieldRequest(@NotBlank @Size(max = 40) String fieldCode, Boolean required, Integer sortOrder,
			Boolean active) {
	}

	// ── property requests ──────────────────────────────────────────────────────────────────────────────────────
	public record AmenityChoice(@NotBlank @Size(max = 40) String code, Boolean enabled, @Size(max = 500) String notes) {
	}

	/**
	 * {@code amenities}: the full property-level selection (unlisted ones are switched off). {@code fields}: values
	 * by field code; null clears one. Either may be omitted to leave that part alone.
	 */
	public record ConfigurationRequest(List<@Valid AmenityChoice> amenities, Map<String, JsonNode> fields) {
	}

	public record RoomAmenitiesRequest(@NotNull List<@Valid AmenityChoice> amenities) {
	}
}
