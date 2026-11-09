package com.pg.config;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pg.common.BadRequestException;
import com.pg.common.ForbiddenException;
import com.pg.common.NotFoundException;
import com.pg.config.ConfigDtos.AmenityChoice;
import com.pg.config.ConfigDtos.AmenityState;
import com.pg.config.ConfigDtos.ConfigurationRequest;
import com.pg.config.ConfigDtos.FieldState;
import com.pg.config.ConfigDtos.PropertyConfiguration;
import com.pg.config.ConfigDtos.RoomAmenities;
import com.pg.config.ConfigDtos.RoomAmenitiesRequest;
import com.pg.config.ConfigDtos.TypeAmenityDto;
import com.pg.config.ConfigDtos.TypeFieldDto;
import com.pg.pg.OwnerPgAccess;
import com.pg.pg.Pg;
import com.pg.pg.PgRepository;
import com.pg.room.RoomRepository;
import com.pg.user.Role;
import com.pg.user.UserEntity;
import com.pg.user.UserRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

/**
 * What one property (and its rooms) has selected, against what its property type asks for. The property type's
 * amenity and field lists come from the catalog, so a new type, amenity or field needs no change here.
 */
@Service
public class PropertyConfigurationService {

	private final ObjectMapper json = new ObjectMapper();
	private final ConfigCatalogService catalog;
	private final PgRepository pgs;
	private final UserRepository users;
	private final OwnerPgAccess access;
	private final RoomRepository rooms;
	private final AmenityRepository amenities;
	private final PropertyAmenityRepository propertyAmenities;
	private final RoomAmenityRepository roomAmenities;
	private final PropertyFieldDefinitionRepository fieldDefs;
	private final PropertyFieldValueRepository fieldValues;

	public PropertyConfigurationService(ConfigCatalogService catalog, PgRepository pgs, UserRepository users,
			OwnerPgAccess access, RoomRepository rooms, AmenityRepository amenities,
			PropertyAmenityRepository propertyAmenities, RoomAmenityRepository roomAmenities,
			PropertyFieldDefinitionRepository fieldDefs, PropertyFieldValueRepository fieldValues) {
		this.catalog = catalog;
		this.pgs = pgs;
		this.users = users;
		this.access = access;
		this.rooms = rooms;
		this.amenities = amenities;
		this.propertyAmenities = propertyAmenities;
		this.roomAmenities = roomAmenities;
		this.fieldDefs = fieldDefs;
		this.fieldValues = fieldValues;
	}

	// ── property level ─────────────────────────────────────────────────────────────────────────────────────────
	@Transactional(readOnly = true)
	public PropertyConfiguration get(Long propertyId, Authentication auth) {
		return view(property(propertyId, auth, false));
	}

	@Transactional
	public PropertyConfiguration save(Long propertyId, ConfigurationRequest req, Authentication auth) {
		Pg pg = property(propertyId, auth, true);
		String type = catalog.requireType(pg.getStayType());
		if (req.amenities() != null) {
			pg.setAmenities(saveAmenities(pg.getId(), type, req.amenities()));
		}
		if (req.fields() != null) {
			saveFields(pg.getId(), type, req.fields());
		}
		return view(pg);
	}

	private PropertyConfiguration view(Pg pg) {
		String type = catalog.requireType(pg.getStayType());
		Map<Long, PropertyAmenity> saved = new HashMap<>();
		propertyAmenities.findByPgId(pg.getId()).forEach(p -> saved.put(p.getAmenity().getId(), p));
		List<AmenityState> amenityStates = new ArrayList<>();
		for (TypeAmenityDto m : catalog.amenitiesOf(type)) {
			if (!m.amenity().applicableLevel().appliesTo(AmenityLevel.PROPERTY)) {
				continue;
			}
			PropertyAmenity p = saved.get(m.amenity().id());
			amenityStates.add(new AmenityState(m.amenity(), m.defaultSelected(), m.required(),
					p != null && p.isEnabled(), p != null ? p.getNotes() : null));
		}
		Map<Long, PropertyFieldValue> values = new HashMap<>();
		fieldValues.findByPgId(pg.getId()).forEach(v -> values.put(v.getField().getId(), v));
		List<FieldState> fieldStates = new ArrayList<>();
		for (TypeFieldDto m : catalog.fieldsOf(type)) {
			PropertyFieldValue v = values.get(m.field().id());
			fieldStates.add(new FieldState(m.field(), m.required(), v == null ? null : read(m.field().fieldType(), v)));
		}
		return new PropertyConfiguration(pg.getId(), pg.getName(), type, catalog.typeLabel(type), amenityStates,
				fieldStates);
	}

	/**
	 * Sets a property's amenities by code (used when a PG is created or edited), e.g. {@code ["WIFI","LIFT"]}.
	 * The codes are the full selection for the property's current type.
	 */
	@Transactional
	public void applyAmenities(Pg pg, List<String> codes) {
		String type = catalog.requireType(pg.getStayType());
		List<AmenityChoice> choices = codes.stream().map(c -> new AmenityChoice(c, true, null)).toList();
		pg.setAmenities(saveAmenities(pg.getId(), type, choices));
	}

	/** Saves the selection and returns the chosen names as text, which the older public listing still reads. */
	private String saveAmenities(Long pgId, String type, List<AmenityChoice> choices) {
		Map<Amenity, AmenityChoice> chosen = resolve(type, AmenityLevel.PROPERTY, choices);
		Map<Long, PropertyAmenity> existing = new HashMap<>();
		propertyAmenities.findByPgId(pgId).forEach(p -> existing.put(p.getAmenity().getId(), p));
		Set<Long> keep = new HashSet<>();
		for (Map.Entry<Amenity, AmenityChoice> e : chosen.entrySet()) {
			PropertyAmenity row = existing.get(e.getKey().getId());
			if (row == null) {
				row = new PropertyAmenity();
				row.setPgId(pgId);
				row.setAmenity(e.getKey());
			}
			row.setEnabled(!Boolean.FALSE.equals(e.getValue().enabled()));
			row.setNotes(blankToNull(e.getValue().notes()));
			propertyAmenities.save(row);
			keep.add(e.getKey().getId());
		}
		// Selection is the full list: anything not sent is switched off, never deleted.
		existing.forEach((amenityId, row) -> {
			if (!keep.contains(amenityId) && row.isEnabled()) {
				row.setEnabled(false);
				propertyAmenities.save(row);
			}
		});
		requireMandatory(type, AmenityLevel.PROPERTY, chosen);
		return chosen.entrySet().stream().filter(e -> !Boolean.FALSE.equals(e.getValue().enabled()))
				.map(e -> e.getKey().getName()).collect(java.util.stream.Collectors.joining(", "));
	}

	private void saveFields(Long pgId, String type, Map<String, JsonNode> incoming) {
		Map<String, TypeFieldDto> offered = new LinkedHashMap<>();
		catalog.fieldsOf(type).forEach(f -> offered.put(f.field().code(), f));
		Map<Long, PropertyFieldValue> existing = new HashMap<>();
		fieldValues.findByPgId(pgId).forEach(v -> existing.put(v.getField().getId(), v));

		Set<String> seen = new HashSet<>();
		for (Map.Entry<String, JsonNode> e : incoming.entrySet()) {
			String code = e.getKey() == null ? "" : e.getKey().trim().toUpperCase(Locale.ROOT);
			TypeFieldDto mapping = offered.get(code);
			if (mapping == null) {
				throw new BadRequestException("Field " + e.getKey() + " is not available for " + type);
			}
			if (!seen.add(code)) {
				throw new BadRequestException("Field " + code + " is listed twice");
			}
			PropertyFieldDefinition def = fieldDefs.findById(mapping.field().id()).orElseThrow();
			JsonNode value = e.getValue();
			PropertyFieldValue row = existing.get(def.getId());
			if (value == null || value.isNull()) {
				if (row != null) {
					fieldValues.delete(row);
					existing.remove(def.getId());
				}
				continue;
			}
			if (row == null) {
				row = new PropertyFieldValue();
				row.setPgId(pgId);
				row.setField(def);
				existing.put(def.getId(), row);
			}
			write(def, value, row);
			fieldValues.save(row);
		}
		for (TypeFieldDto m : offered.values()) {
			if (m.required() && !existing.containsKey(m.field().id())) {
				throw new BadRequestException(m.field().label() + " is required");
			}
		}
	}

	// ── room level ─────────────────────────────────────────────────────────────────────────────────────────────
	@Transactional(readOnly = true)
	public RoomAmenities getRoom(Long propertyId, Long roomId, Authentication auth) {
		Pg pg = property(propertyId, auth, false);
		requireRoom(pg.getId(), roomId);
		return roomView(pg, roomId);
	}

	@Transactional
	public RoomAmenities saveRoom(Long propertyId, Long roomId, RoomAmenitiesRequest req, Authentication auth) {
		Pg pg = property(propertyId, auth, true);
		requireRoom(pg.getId(), roomId);
		String type = catalog.requireType(pg.getStayType());
		Map<Amenity, AmenityChoice> chosen = resolve(type, AmenityLevel.ROOM, req.amenities());
		Map<Long, RoomAmenity> existing = new HashMap<>();
		roomAmenities.findByPgIdAndRoomId(pg.getId(), roomId).forEach(r -> existing.put(r.getAmenity().getId(), r));
		Set<Long> keep = new HashSet<>();
		for (Map.Entry<Amenity, AmenityChoice> e : chosen.entrySet()) {
			RoomAmenity row = existing.get(e.getKey().getId());
			if (row == null) {
				row = new RoomAmenity();
				row.setPgId(pg.getId());
				row.setRoomId(roomId);
				row.setAmenity(e.getKey());
			}
			row.setEnabled(!Boolean.FALSE.equals(e.getValue().enabled()));
			roomAmenities.save(row);
			keep.add(e.getKey().getId());
		}
		existing.forEach((amenityId, row) -> {
			if (!keep.contains(amenityId) && row.isEnabled()) {
				row.setEnabled(false);
				roomAmenities.save(row);
			}
		});
		requireMandatory(type, AmenityLevel.ROOM, chosen);
		return roomView(pg, roomId);
	}

	private RoomAmenities roomView(Pg pg, Long roomId) {
		String type = catalog.requireType(pg.getStayType());
		Map<Long, RoomAmenity> saved = new HashMap<>();
		roomAmenities.findByPgIdAndRoomId(pg.getId(), roomId).forEach(r -> saved.put(r.getAmenity().getId(), r));
		List<AmenityState> states = new ArrayList<>();
		for (TypeAmenityDto m : catalog.amenitiesOf(type)) {
			if (!m.amenity().applicableLevel().appliesTo(AmenityLevel.ROOM)) {
				continue;
			}
			RoomAmenity r = saved.get(m.amenity().id());
			states.add(new AmenityState(m.amenity(), m.defaultSelected(), m.required(), r != null && r.isEnabled(),
					null));
		}
		return new RoomAmenities(roomId, states);
	}

	private void requireRoom(Long pgId, Long roomId) {
		rooms.findByPgIdAndId(pgId, roomId).orElseThrow(() -> new NotFoundException("Room not found"));
	}

	// ── shared amenity validation ──────────────────────────────────────────────────────────────────────────────
	/** Each code must be an active amenity that this property type offers at this level; no duplicates. */
	private Map<Amenity, AmenityChoice> resolve(String type, AmenityLevel level, List<AmenityChoice> choices) {
		Map<String, TypeAmenityDto> offered = new HashMap<>();
		catalog.amenitiesOf(type).forEach(m -> offered.put(m.amenity().code(), m));
		Map<Amenity, AmenityChoice> out = new LinkedHashMap<>();
		Set<String> seen = new HashSet<>();
		for (AmenityChoice c : choices) {
			String code = c.code().trim().toUpperCase(Locale.ROOT);
			if (!seen.add(code)) {
				throw new BadRequestException("Amenity " + code + " is listed twice");
			}
			TypeAmenityDto m = offered.get(code);
			if (m == null) {
				throw new BadRequestException("Amenity " + code + " is not available for " + type);
			}
			if (!m.amenity().applicableLevel().appliesTo(level)) {
				throw new BadRequestException(
						"Amenity " + code + " cannot be set at " + level.name().toLowerCase(Locale.ROOT) + " level");
			}
			out.put(amenities.findById(m.amenity().id()).orElseThrow(), c);
		}
		return out;
	}

	private void requireMandatory(String type, AmenityLevel level, Map<Amenity, AmenityChoice> chosen) {
		Set<String> on = new HashSet<>();
		chosen.forEach((a, c) -> {
			if (!Boolean.FALSE.equals(c.enabled())) {
				on.add(a.getCode());
			}
		});
		for (TypeAmenityDto m : catalog.amenitiesOf(type)) {
			if (m.required() && m.amenity().applicableLevel().appliesTo(level) && !on.contains(m.amenity().code())) {
				throw new BadRequestException(m.amenity().name() + " is required for " + type);
			}
		}
	}

	// ── field values: validate and store by field type ─────────────────────────────────────────────────────────
	private void write(PropertyFieldDefinition def, JsonNode v, PropertyFieldValue row) {
		row.setValueText(null);
		row.setValueNumber(null);
		row.setValueBoolean(null);
		row.setValueJson(null);
		String label = def.getLabel();
		JsonNode rules = catalog.rules(def);
		switch (def.getFieldType()) {
		case TEXT -> {
			String s = text(v, label, 2000);
			if (rules != null) {
				if (rules.get("minLength") != null && s.length() < rules.get("minLength").asInt()) {
					throw new BadRequestException(
							label + " must be at least " + rules.get("minLength").asInt() + " characters");
				}
				if (rules.get("maxLength") != null && s.length() > rules.get("maxLength").asInt()) {
					throw new BadRequestException(
							label + " must be at most " + rules.get("maxLength").asInt() + " characters");
				}
				if (rules.get("pattern") != null && !Pattern.matches(rules.get("pattern").asString(), s)) {
					throw new BadRequestException(label + " is not in the expected format");
				}
			}
			row.setValueText(s);
		}
		case NUMBER, DECIMAL -> {
			BigDecimal n = number(v, label);
			if (def.getFieldType() == FieldType.NUMBER && n.stripTrailingZeros().scale() > 0) {
				throw new BadRequestException(label + " must be a whole number");
			}
			if (rules != null) {
				if (rules.get("min") != null && n.compareTo(rules.get("min").decimalValue()) < 0) {
					throw new BadRequestException(
							label + " must be at least " + rules.get("min").decimalValue().toPlainString());
				}
				if (rules.get("max") != null && n.compareTo(rules.get("max").decimalValue()) > 0) {
					throw new BadRequestException(
							label + " must be at most " + rules.get("max").decimalValue().toPlainString());
				}
			}
			row.setValueNumber(n);
		}
		case BOOLEAN -> {
			if (!v.isBoolean()) {
				throw new BadRequestException(label + " must be true or false");
			}
			row.setValueBoolean(v.booleanValue());
		}
		case DATE -> {
			String s = text(v, label, 20);
			try {
				row.setValueText(LocalDate.parse(s).toString());
			} catch (DateTimeParseException e) {
				throw new BadRequestException(label + " must be a date like 2026-01-31");
			}
		}
		case TIME -> {
			String s = text(v, label, 20);
			try {
				row.setValueText(LocalTime.parse(s).toString());
			} catch (DateTimeParseException e) {
				throw new BadRequestException(label + " must be a time like 14:30");
			}
		}
		case DROPDOWN, RADIO -> {
			String s = text(v, label, 100);
			if (!catalog.optionValues(def).contains(s)) {
				throw new BadRequestException(label + " must be one of its options");
			}
			row.setValueText(s);
		}
		case MULTI_SELECT, CHECKBOX -> {
			if (!v.isArray()) {
				throw new BadRequestException(label + " must be a list of options");
			}
			List<String> allowed = catalog.optionValues(def);
			ArrayNode out = json.createArrayNode();
			Set<String> seen = new HashSet<>();
			for (int i = 0; i < v.size(); i++) {
				String s = text(v.get(i), label, 100);
				if (!allowed.contains(s)) {
					throw new BadRequestException(label + ": " + s + " is not one of its options");
				}
				if (seen.add(s)) {
					out.add(s);
				}
			}
			row.setValueJson(json.writeValueAsString(out));
		}
		case IMAGE, DOCUMENT -> row.setValueText(text(v, label, 500));
		}
	}

	private JsonNode read(FieldType type, PropertyFieldValue v) {
		return switch (type) {
		case NUMBER -> v.getValueNumber() == null ? null : json.valueToTree(v.getValueNumber().longValue());
		case DECIMAL -> v.getValueNumber() == null ? null : json.valueToTree(v.getValueNumber().stripTrailingZeros());
		case BOOLEAN -> v.getValueBoolean() == null ? null : json.valueToTree(v.getValueBoolean());
		case MULTI_SELECT, CHECKBOX -> v.getValueJson() == null ? null : json.readTree(v.getValueJson());
		default -> v.getValueText() == null ? null : json.valueToTree(v.getValueText());
		};
	}

	private static String text(JsonNode v, String label, int max) {
		if (v == null || !v.isString() || v.asString().isBlank()) {
			throw new BadRequestException(label + " must be a non-empty text");
		}
		String s = v.asString().trim();
		if (s.length() > max) {
			throw new BadRequestException(label + " is too long");
		}
		return s;
	}

	private static BigDecimal number(JsonNode v, String label) {
		try {
			if (v.isNumber()) {
				return v.decimalValue();
			}
			if (v.isString()) {
				return new BigDecimal(v.asString().trim());
			}
		} catch (NumberFormatException e) {
			// falls through to the error below
		}
		throw new BadRequestException(label + " must be a number");
	}

	// ── access ─────────────────────────────────────────────────────────────────────────────────────────────────
	/** Super admin: any property. Owner: their own. Manager: their own PG, read only. Residents never get here. */
	private Pg property(Long propertyId, Authentication auth, boolean write) {
		UserEntity user = users.findByUsername(auth.getName()).orElseThrow(() -> new ForbiddenException("Unknown user"));
		boolean admin = user.getRole() == Role.SUPER_ADMIN;
		if (!admin && !access.owns(user, propertyId)) {
			throw new ForbiddenException("You do not have access to this property");
		}
		if (write && user.getRole() != Role.OWNER && !admin) {
			throw new ForbiddenException("Only an owner can change the property configuration");
		}
		return pgs.findById(propertyId).orElseThrow(() -> new NotFoundException("Property not found"));
	}

	private static String blankToNull(String s) {
		return s == null || s.isBlank() ? null : s.trim();
	}
}
