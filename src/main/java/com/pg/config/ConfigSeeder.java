package com.pg.config;

import java.util.List;
import java.util.Map;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.pg.lookup.LookupService;
import com.pg.lookup.LookupValue;
import com.pg.lookup.LookupValueRepository;

/**
 * Starting data for the configuration tables. Each table is filled only while it is empty, so whatever the super
 * admin later adds, edits or retires is never overwritten. It is sample data: every row can be changed through
 * the admin APIs, and none of it is referenced by code.
 */
@Component
@Order(2) // after LookupSeeder, which fills STAY_TYPE when that category is empty
public class ConfigSeeder implements ApplicationRunner {

	private record Cat(String code, String name, String description) {
	}

	private record Am(String code, String name, String category, AmenityLevel level, String icon) {
	}

	private record Mapping(String amenity, boolean defaultSelected, boolean required) {
	}

	private final LookupValueRepository lookupValues;
	private final AmenityCategoryRepository categories;
	private final AmenityRepository amenities;
	private final StayTypeAmenityRepository typeAmenities;
	private final PropertyFieldDefinitionRepository fields;
	private final StayTypeFieldRepository typeFields;

	public ConfigSeeder(LookupValueRepository lookupValues, AmenityCategoryRepository categories,
			AmenityRepository amenities, StayTypeAmenityRepository typeAmenities,
			PropertyFieldDefinitionRepository fields, StayTypeFieldRepository typeFields) {
		this.lookupValues = lookupValues;
		this.categories = categories;
		this.amenities = amenities;
		this.typeAmenities = typeAmenities;
		this.fields = fields;
		this.typeFields = typeFields;
	}

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		seedPropertyTypes();
		seedCategories();
		seedAmenities();
		seedAmenityMappings();
		seedFields();
		seedFieldMappings();
	}

	/** Extra property types, added when missing, on a database that already has its STAY_TYPE list. */
	private void seedPropertyTypes() {
		if (lookupValues.countByCategory(LookupService.STAY_TYPE) == 0) {
			return;
		}
		int sort = 6;
		for (String[] t : new String[][] { { "HOTEL", "Hotel", "Room" }, { "RESORT", "Resort", "Room" },
				{ "VILLA", "Villa", "Villa" }, { "INDEPENDENT_HOUSE", "Independent House", "House" } }) {
			if (lookupValues.findByCategoryAndCode(LookupService.STAY_TYPE, t[0]).isEmpty()) {
				lookupValues.save(new LookupValue(LookupService.STAY_TYPE, t[0], t[1], t[2], sort));
			}
			sort++;
		}
	}

	private void seedCategories() {
		if (categories.count() > 0) {
			return;
		}
		int sort = 1;
		for (Cat c : List.of(new Cat("ROOM", "Room", "In-room furniture and fittings"),
				new Cat("BATHROOM", "Bathroom", "Bathroom facilities"),
				new Cat("FOOD", "Food", "Meals and dining"),
				new Cat("SECURITY", "Security", "Safety and surveillance"),
				new Cat("UTILITIES", "Utilities", "Appliances and power"),
				new Cat("PARKING", "Parking", "Vehicle parking"),
				new Cat("RECREATION", "Recreation", "Leisure and fitness"),
				new Cat("COMMON_FACILITIES", "Common Facilities", "Shared areas and services"),
				new Cat("CONNECTIVITY", "Connectivity", "Internet and communication"),
				new Cat("HOUSEKEEPING", "Housekeeping", "Cleaning and upkeep"))) {
			AmenityCategory e = new AmenityCategory();
			e.setCode(c.code());
			e.setName(c.name());
			e.setDescription(c.description());
			e.setSortOrder(sort++);
			categories.save(e);
		}
	}

	private void seedAmenities() {
		if (amenities.count() > 0) {
			return;
		}
		AmenityLevel p = AmenityLevel.PROPERTY;
		AmenityLevel r = AmenityLevel.ROOM;
		AmenityLevel b = AmenityLevel.BOTH;
		for (Am a : List.of(new Am("BED", "Bed", "ROOM", r, "bed"),
				new Am("MATTRESS", "Mattress", "ROOM", r, "bed-outline"),
				new Am("WARDROBE", "Wardrobe", "ROOM", r, "file-tray-stacked-outline"),
				new Am("AC", "Air Conditioning", "ROOM", b, "snow-outline"),
				new Am("TV", "TV", "ROOM", b, "tv-outline"),
				new Am("BALCONY", "Balcony", "ROOM", b, "home-outline"),
				new Am("MODULAR_KITCHEN", "Modular Kitchen", "ROOM", b, "restaurant-outline"),
				new Am("ATTACHED_BATHROOM", "Attached Bathroom", "BATHROOM", r, "water-outline"),
				new Am("HOT_WATER", "Hot Water", "BATHROOM", b, "flame-outline"),
				new Am("FOOD", "Food", "FOOD", p, "fast-food-outline"),
				new Am("RESTAURANT", "Restaurant", "FOOD", p, "restaurant-outline"),
				new Am("CCTV", "CCTV", "SECURITY", p, "videocam-outline"),
				new Am("WASHING_MACHINE", "Washing Machine", "UTILITIES", p, "shirt-outline"),
				new Am("REFRIGERATOR", "Refrigerator", "UTILITIES", b, "cube-outline"),
				new Am("POWER_BACKUP", "Power Backup", "UTILITIES", p, "flash-outline"),
				new Am("PARKING", "Parking", "PARKING", p, "car-outline"),
				new Am("GYM", "Gym", "RECREATION", p, "barbell-outline"),
				new Am("SWIMMING_POOL", "Swimming Pool", "RECREATION", p, "water-outline"),
				new Am("LIFT", "Lift", "COMMON_FACILITIES", p, "swap-vertical-outline"),
				new Am("RECEPTION", "Reception", "COMMON_FACILITIES", p, "people-outline"),
				new Am("WIFI", "Wi-Fi", "CONNECTIVITY", b, "wifi-outline"),
				new Am("HOUSEKEEPING", "Housekeeping", "HOUSEKEEPING", p, "sparkles-outline"),
				new Am("ROOM_SERVICE", "Room Service", "HOUSEKEEPING", p, "notifications-outline"))) {
			Amenity e = new Amenity();
			e.setCode(a.code());
			e.setName(a.name());
			e.setCategory(categories.findByCode(a.category()).orElseThrow());
			e.setApplicableLevel(a.level());
			e.setIcon(a.icon());
			amenities.save(e);
		}
	}

	private void seedAmenityMappings() {
		if (typeAmenities.count() > 0) {
			return;
		}
		mapAmenities("PG", List.of(new Mapping("BED", true, true), new Mapping("MATTRESS", true, false),
				new Mapping("WARDROBE", true, false), new Mapping("WIFI", true, false),
				new Mapping("FOOD", false, false), new Mapping("WASHING_MACHINE", false, false),
				new Mapping("HOUSEKEEPING", false, false), new Mapping("CCTV", false, false),
				new Mapping("HOT_WATER", false, false)));
		mapAmenities("APARTMENT", List.of(new Mapping("MODULAR_KITCHEN", false, false),
				new Mapping("REFRIGERATOR", false, false), new Mapping("BALCONY", false, false),
				new Mapping("PARKING", false, false), new Mapping("LIFT", false, false),
				new Mapping("SWIMMING_POOL", false, false), new Mapping("GYM", false, false),
				new Mapping("POWER_BACKUP", false, false)));
		mapAmenities("HOTEL", List.of(new Mapping("ROOM_SERVICE", true, false), new Mapping("AC", true, false),
				new Mapping("TV", true, false), new Mapping("WIFI", true, false),
				new Mapping("ATTACHED_BATHROOM", true, false), new Mapping("RESTAURANT", false, false),
				new Mapping("RECEPTION", true, false), new Mapping("HOUSEKEEPING", true, false)));
	}

	private void mapAmenities(String type, List<Mapping> rows) {
		int sort = 1;
		for (Mapping m : rows) {
			StayTypeAmenity e = new StayTypeAmenity();
			e.setStayTypeCode(type);
			e.setAmenity(amenities.findByCode(m.amenity()).orElseThrow());
			e.setDefaultSelected(m.defaultSelected());
			e.setRequired(m.required());
			e.setSortOrder(sort++);
			typeAmenities.save(e);
		}
	}

	private void seedFields() {
		if (fields.count() > 0) {
			return;
		}
		field("GATE_CLOSING_TIME", "Gate closing time", FieldType.TIME, "Rules", "22:30", null, null, 1);
		field("VISITORS_ALLOWED", "Visitors allowed", FieldType.BOOLEAN, "Rules", null, null, null, 2);
		field("MEAL_PLAN", "Meals provided", FieldType.MULTI_SELECT, "Food", null,
				"[{\"value\":\"BREAKFAST\",\"label\":\"Breakfast\"},{\"value\":\"LUNCH\",\"label\":\"Lunch\"},"
						+ "{\"value\":\"DINNER\",\"label\":\"Dinner\"}]",
				null, 3);
		field("FURNISHING", "Furnishing", FieldType.DROPDOWN, "Unit", null,
				"[{\"value\":\"FURNISHED\",\"label\":\"Furnished\"},{\"value\":\"SEMI_FURNISHED\",\"label\":\"Semi-furnished\"},"
						+ "{\"value\":\"UNFURNISHED\",\"label\":\"Unfurnished\"}]",
				null, 4);
		field("PARKING_SLOTS", "Parking slots", FieldType.NUMBER, "Parking", null, null, "{\"min\":0}", 5);
		field("CHECK_IN_TIME", "Check-in time", FieldType.TIME, "Stay", "14:00", null, null, 6);
		field("CHECK_OUT_TIME", "Check-out time", FieldType.TIME, "Stay", "11:00", null, null, 7);
		field("STAR_RATING", "Star rating", FieldType.NUMBER, "Stay", null, null, "{\"min\":1,\"max\":5}", 8);
	}

	private void field(String code, String label, FieldType type, String category, String placeholder, String options,
			String rules, int sort) {
		PropertyFieldDefinition f = new PropertyFieldDefinition();
		f.setCode(code);
		f.setLabel(label);
		f.setFieldType(type);
		f.setCategory(category);
		f.setPlaceholder(placeholder);
		f.setOptions(options);
		f.setValidationRules(rules);
		f.setSortOrder(sort);
		fields.save(f);
	}

	private void seedFieldMappings() {
		if (typeFields.count() > 0) {
			return;
		}
		Map<String, List<String>> byType = Map.of("PG", List.of("GATE_CLOSING_TIME", "VISITORS_ALLOWED", "MEAL_PLAN"),
				"APARTMENT", List.of("FURNISHING", "PARKING_SLOTS"), "HOTEL",
				List.of("CHECK_IN_TIME", "CHECK_OUT_TIME", "STAR_RATING"));
		byType.forEach((type, codes) -> {
			int sort = 1;
			for (String code : codes) {
				StayTypeField e = new StayTypeField();
				e.setStayTypeCode(type);
				e.setField(fields.findByCode(code).orElseThrow());
				e.setSortOrder(sort++);
				typeFields.save(e);
			}
		});
	}
}
