package com.pg.config;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pg.common.BadRequestException;
import com.pg.common.NotFoundException;
import com.pg.config.ConfigDtos.AmenityDto;
import com.pg.config.ConfigDtos.CategoryDto;
import com.pg.config.ConfigDtos.CreateAmenityRequest;
import com.pg.config.ConfigDtos.CreateFieldRequest;
import com.pg.config.ConfigDtos.FieldDto;
import com.pg.config.ConfigDtos.MapAmenityRequest;
import com.pg.config.ConfigDtos.MapFieldRequest;
import com.pg.config.ConfigDtos.PropertyTypeDetail;
import com.pg.config.ConfigDtos.PropertyTypeDto;
import com.pg.config.ConfigDtos.TypeAmenityDto;
import com.pg.config.ConfigDtos.TypeFieldDto;
import com.pg.config.ConfigDtos.UpdateAmenityRequest;
import com.pg.config.ConfigDtos.UpdateFieldRequest;

import com.pg.lookup.LookupService;
import com.pg.lookup.LookupValueRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The configuration catalog: which property types exist (the STAY_TYPE lookup), which amenities and custom fields
 * exist, and which of them each type offers. Everything is data; nothing here names a specific type or amenity.
 */
@Service
public class ConfigCatalogService {

	private static final Set<String> RULE_KEYS = Set.of("minLength", "maxLength", "pattern", "min", "max");

	private final ObjectMapper json = new ObjectMapper();
	private final LookupService lookups;
	private final LookupValueRepository lookupValues;
	private final AmenityCategoryRepository categories;
	private final AmenityRepository amenities;
	private final StayTypeAmenityRepository typeAmenities;
	private final PropertyFieldDefinitionRepository fields;
	private final StayTypeFieldRepository typeFields;

	public ConfigCatalogService(LookupService lookups, LookupValueRepository lookupValues,
			AmenityCategoryRepository categories, AmenityRepository amenities,
			StayTypeAmenityRepository typeAmenities, PropertyFieldDefinitionRepository fields,
			StayTypeFieldRepository typeFields) {
		this.lookups = lookups;
		this.lookupValues = lookupValues;
		this.categories = categories;
		this.amenities = amenities;
		this.typeAmenities = typeAmenities;
		this.fields = fields;
		this.typeFields = typeFields;
	}

	// ── property types (the STAY_TYPE lookup) ──────────────────────────────────────────────────────────────────
	public List<PropertyTypeDto> propertyTypes() {
		return lookups.list(LookupService.STAY_TYPE).stream()
				.map(i -> new PropertyTypeDto(i.code(), i.label(), unit(i.extra()))).toList();
	}

	/** The normalised code of an active property type, or a 404. */
	public String requireType(String typeCode) {
		String code = typeCode == null ? "" : typeCode.trim().toUpperCase(Locale.ROOT);
		lookups.activeLabel(LookupService.STAY_TYPE, code)
				.orElseThrow(() -> new NotFoundException("Unknown property type " + typeCode));
		return code;
	}

	public String typeLabel(String code) {
		return lookups.activeLabel(LookupService.STAY_TYPE, code).orElse(code);
	}

	public PropertyTypeDetail propertyType(String typeCode) {
		String code = requireType(typeCode);
		String extra = lookupValues.findByCategoryAndCode(LookupService.STAY_TYPE, code)
				.map(v -> v.getExtra()).orElse(null);
		return new PropertyTypeDetail(code, typeLabel(code), unit(extra), amenitiesOf(code), fieldsOf(code));
	}

	private static String unit(String extra) {
		return extra == null || extra.isBlank() ? "Room" : extra;
	}

	// ── reads ──────────────────────────────────────────────────────────────────────────────────────────────────
	public List<CategoryDto> categories() {
		return categories.findByActiveTrueOrderBySortOrderAscNameAsc().stream().map(ConfigCatalogService::toDto)
				.toList();
	}

	public List<AmenityDto> amenities() {
		return amenities.findByActiveTrueOrderByCategorySortOrderAscNameAsc().stream()
				.map(ConfigCatalogService::toDto).toList();
	}

	/** Active mappings of a type whose amenity is itself active. */
	public List<TypeAmenityDto> amenitiesOf(String typeCode) {
		String code = requireType(typeCode);
		return typeAmenities.findByStayTypeCodeAndActiveTrueOrderBySortOrderAsc(code).stream()
				.filter(m -> m.getAmenity().isActive()).map(ConfigCatalogService::toDto).toList();
	}

	public List<TypeFieldDto> fieldsOf(String typeCode) {
		String code = requireType(typeCode);
		return typeFields.findByStayTypeCodeAndActiveTrueOrderBySortOrderAsc(code).stream()
				.filter(m -> m.getField().isActive()).map(this::toDto).toList();
	}

	// ── admin: create masters ──────────────────────────────────────────────────────────────────────────────────
	@Transactional
	public AmenityDto createAmenity(CreateAmenityRequest r) {
		String code = r.code().trim().toUpperCase(Locale.ROOT);
		if (amenities.findByCode(code).isPresent()) {
			throw new BadRequestException("Amenity " + code + " already exists");
		}
		AmenityCategory category = categories.findByCode(r.categoryCode().trim().toUpperCase(Locale.ROOT))
				.filter(AmenityCategory::isActive)
				.orElseThrow(() -> new BadRequestException("Unknown amenity category " + r.categoryCode()));
		Amenity a = new Amenity();
		a.setCode(code);
		a.setName(r.name().trim());
		a.setCategory(category);
		a.setDescription(blankToNull(r.description()));
		a.setIcon(blankToNull(r.icon()));
		a.setApplicableLevel(r.applicableLevel() != null ? r.applicableLevel() : AmenityLevel.BOTH);
		return toDto(amenities.save(a));
	}

	@Transactional
	public FieldDto createField(CreateFieldRequest r) {
		String code = r.code().trim().toUpperCase(Locale.ROOT);
		if (fields.findByCode(code).isPresent()) {
			throw new BadRequestException("Field " + code + " already exists");
		}
		PropertyFieldDefinition f = new PropertyFieldDefinition();
		f.setCode(code);
		f.setLabel(r.label().trim());
		f.setDescription(blankToNull(r.description()));
		f.setFieldType(r.fieldType());
		f.setCategory(blankToNull(r.category()));
		f.setPlaceholder(blankToNull(r.placeholder()));
		f.setDefaultValue(blankToNull(r.defaultValue()));
		f.setRequired(Boolean.TRUE.equals(r.required()));
		f.setSortOrder(r.sortOrder() != null ? r.sortOrder() : 0);
		f.setOptions(normaliseOptions(r.fieldType(), r.options()));
		f.setValidationRules(normaliseRules(r.fieldType(), r.validationRules()));
		if (f.getDefaultValue() != null && r.fieldType().hasOptions() && !r.fieldType().isMulti()
				&& !optionValues(f).contains(f.getDefaultValue())) {
			throw new BadRequestException("Default value is not one of the options");
		}
		return toDto(fields.save(f));
	}

	// ── admin: list everything (inactive included) and edit masters ────────────────────────────────────────────
	public List<AmenityDto> allAmenities() {
		return amenities.findAllByOrderByCategorySortOrderAscNameAsc().stream().map(ConfigCatalogService::toDto)
				.toList();
	}

	public List<FieldDto> allFields() {
		return fields.findAllByOrderBySortOrderAscLabelAsc().stream().map(this::toDto).toList();
	}

	@Transactional
	public AmenityDto updateAmenity(Long id, UpdateAmenityRequest r) {
		Amenity a = amenities.findById(id).orElseThrow(() -> new NotFoundException("Amenity not found"));
		if (r.name() != null) {
			if (r.name().isBlank()) {
				throw new BadRequestException("Name cannot be empty");
			}
			a.setName(r.name().trim());
		}
		if (r.categoryCode() != null && !r.categoryCode().isBlank()) {
			a.setCategory(categories.findByCode(r.categoryCode().trim().toUpperCase(Locale.ROOT))
					.filter(AmenityCategory::isActive)
					.orElseThrow(() -> new BadRequestException("Unknown amenity category " + r.categoryCode())));
		}
		if (r.description() != null) a.setDescription(blankToNull(r.description()));
		if (r.icon() != null) a.setIcon(blankToNull(r.icon()));
		if (r.applicableLevel() != null) a.setApplicableLevel(r.applicableLevel());
		if (r.active() != null) a.setActive(r.active());
		return toDto(amenities.save(a));
	}

	@Transactional
	public FieldDto updateField(Long id, UpdateFieldRequest r) {
		PropertyFieldDefinition f = fields.findById(id).orElseThrow(() -> new NotFoundException("Field not found"));
		if (r.label() != null) {
			if (r.label().isBlank()) {
				throw new BadRequestException("Label cannot be empty");
			}
			f.setLabel(r.label().trim());
		}
		if (r.description() != null) f.setDescription(blankToNull(r.description()));
		if (r.category() != null) f.setCategory(blankToNull(r.category()));
		if (r.placeholder() != null) f.setPlaceholder(blankToNull(r.placeholder()));
		if (r.defaultValue() != null) {
			f.setDefaultValue(blankToNull(r.defaultValue()));
			if (f.getDefaultValue() != null && f.getFieldType().hasOptions() && !f.getFieldType().isMulti()
					&& !optionValues(f).contains(f.getDefaultValue())) {
				throw new BadRequestException("Default value is not one of the options");
			}
		}
		if (r.required() != null) f.setRequired(r.required());
		if (r.sortOrder() != null) f.setSortOrder(r.sortOrder());
		if (r.active() != null) f.setActive(r.active());
		return toDto(fields.save(f));
	}

	// ── admin: map to a property type ──────────────────────────────────────────────────────────────────────────
	@Transactional
	public TypeAmenityDto mapAmenity(String typeCode, MapAmenityRequest r) {
		String type = requireType(typeCode);
		Amenity amenity = amenities.findByCode(r.amenityCode().trim().toUpperCase(Locale.ROOT))
				.orElseThrow(() -> new BadRequestException("Unknown amenity " + r.amenityCode()));
		if (!amenity.isActive()) {
			throw new BadRequestException("Amenity " + amenity.getCode() + " is inactive");
		}
		StayTypeAmenity m = typeAmenities.findByStayTypeCodeAndAmenityId(type, amenity.getId()).orElseGet(() -> {
			StayTypeAmenity n = new StayTypeAmenity();
			n.setStayTypeCode(type);
			n.setAmenity(amenity);
			return n;
		});
		if (r.defaultSelected() != null) m.setDefaultSelected(r.defaultSelected());
		if (r.required() != null) m.setRequired(r.required());
		if (m.isRequired()) m.setDefaultSelected(true);
		if (r.sortOrder() != null) m.setSortOrder(r.sortOrder());
		if (r.active() != null) m.setActive(r.active());
		return toDto(typeAmenities.save(m));
	}

	@Transactional
	public TypeFieldDto mapField(String typeCode, MapFieldRequest r) {
		String type = requireType(typeCode);
		PropertyFieldDefinition field = fields.findByCode(r.fieldCode().trim().toUpperCase(Locale.ROOT))
				.orElseThrow(() -> new BadRequestException("Unknown field " + r.fieldCode()));
		if (!field.isActive()) {
			throw new BadRequestException("Field " + field.getCode() + " is inactive");
		}
		StayTypeField m = typeFields.findByStayTypeCodeAndFieldId(type, field.getId()).orElseGet(() -> {
			StayTypeField n = new StayTypeField();
			n.setStayTypeCode(type);
			n.setField(field);
			return n;
		});
		if (r.required() != null) m.setRequired(r.required());
		if (r.sortOrder() != null) m.setSortOrder(r.sortOrder());
		if (r.active() != null) m.setActive(r.active());
		return toDto(typeFields.save(m));
	}

	// ── field option / rule handling (shared with the value validation) ────────────────────────────────────────
	/** The allowed values of an option-based field. */
	List<String> optionValues(PropertyFieldDefinition f) {
		List<String> values = new ArrayList<>();
		JsonNode opts = parse(f.getOptions());
		if (opts != null && opts.isArray()) {
			for (int i = 0; i < opts.size(); i++) {
				JsonNode v = opts.get(i).get("value");
				if (v != null) values.add(v.asString());
			}
		}
		return values;
	}

	JsonNode rules(PropertyFieldDefinition f) {
		return parse(f.getValidationRules());
	}

	private String normaliseOptions(FieldType type, JsonNode options) {
		boolean given = options != null && !options.isNull() && !(options.isArray() && options.size() == 0);
		if (!type.hasOptions()) {
			if (given) {
				throw new BadRequestException("Options are only allowed for dropdown, radio, checkbox and multi-select fields");
			}
			return null;
		}
		if (!given || !options.isArray()) {
			throw new BadRequestException(type + " fields need an options list");
		}
		ArrayNode out = json.createArrayNode();
		Set<String> seen = new HashSet<>();
		for (int i = 0; i < options.size(); i++) {
			JsonNode o = options.get(i);
			String value;
			String label;
			if (o.isObject()) {
				JsonNode v = o.get("value");
				value = v == null || v.isNull() ? "" : v.asString().trim();
				JsonNode l = o.get("label");
				label = l == null || l.isNull() || l.asString().isBlank() ? value : l.asString().trim();
			} else if (o.isString()) {
				value = o.asString().trim();
				label = value;
			} else {
				throw new BadRequestException("Each option must be a string or {value, label}");
			}
			if (value.isEmpty() || value.length() > 100) {
				throw new BadRequestException("Option values must be 1-100 characters");
			}
			if (!seen.add(value)) {
				throw new BadRequestException("Duplicate option " + value);
			}
			ObjectNode n = json.createObjectNode();
			n.put("value", value);
			n.put("label", label);
			out.add(n);
		}
		String text = json.writeValueAsString(out);
		if (text.length() > 4000) {
			throw new BadRequestException("Too many options");
		}
		return text;
	}

	private String normaliseRules(FieldType type, JsonNode rules) {
		if (rules == null || rules.isNull() || (rules.isObject() && rules.size() == 0)) {
			return null;
		}
		if (!rules.isObject()) {
			throw new BadRequestException("validationRules must be an object");
		}
		for (String key : rules.propertyNames()) {
			if (!RULE_KEYS.contains(key)) {
				throw new BadRequestException("Unknown validation rule " + key);
			}
		}
		boolean text = type == FieldType.TEXT;
		boolean num = type == FieldType.NUMBER || type == FieldType.DECIMAL;
		for (String key : List.of("minLength", "maxLength", "pattern")) {
			if (rules.get(key) != null && !text) {
				throw new BadRequestException(key + " only applies to TEXT fields");
			}
		}
		for (String key : List.of("min", "max")) {
			if (rules.get(key) != null && !num) {
				throw new BadRequestException(key + " only applies to NUMBER and DECIMAL fields");
			}
		}
		for (String key : List.of("minLength", "maxLength", "min", "max")) {
			if (rules.get(key) != null && !rules.get(key).isNumber()) {
				throw new BadRequestException(key + " must be a number");
			}
		}
		if (rules.get("pattern") != null) {
			try {
				Pattern.compile(rules.get("pattern").asString());
			} catch (PatternSyntaxException e) {
				throw new BadRequestException("pattern is not a valid regular expression");
			}
		}
		String textValue = json.writeValueAsString(rules);
		if (textValue.length() > 2000) {
			throw new BadRequestException("validationRules is too long");
		}
		return textValue;
	}

	JsonNode parse(String text) {
		return text == null || text.isBlank() ? null : json.readTree(text);
	}

	// ── mapping to DTOs ────────────────────────────────────────────────────────────────────────────────────────
	static CategoryDto toDto(AmenityCategory c) {
		return new CategoryDto(c.getId(), c.getCode(), c.getName(), c.getDescription(), c.getSortOrder(), c.isActive());
	}

	static AmenityDto toDto(Amenity a) {
		return new AmenityDto(a.getId(), a.getCode(), a.getName(), a.getDescription(), a.getIcon(),
				a.getApplicableLevel(), a.isActive(), a.getCategory().getCode(), a.getCategory().getName());
	}

	static TypeAmenityDto toDto(StayTypeAmenity m) {
		return new TypeAmenityDto(toDto(m.getAmenity()), m.isDefaultSelected(), m.isRequired(), m.getSortOrder(),
				m.isActive());
	}

	FieldDto toDto(PropertyFieldDefinition f) {
		return new FieldDto(f.getId(), f.getCode(), f.getLabel(), f.getDescription(), f.getFieldType(), f.getCategory(),
				f.getPlaceholder(), f.getDefaultValue(), parse(f.getOptions()), parse(f.getValidationRules()),
				f.isRequired(), f.isActive(), f.getSortOrder());
	}

	TypeFieldDto toDto(StayTypeField m) {
		return new TypeFieldDto(toDto(m.getField()), m.isRequired() || m.getField().isRequired(), m.getSortOrder(),
				m.isActive());
	}

	private static String blankToNull(String s) {
		return s == null || s.isBlank() ? null : s.trim();
	}
}
