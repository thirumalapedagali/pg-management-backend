package com.pg.config;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pg.config.ConfigDtos.AmenityDto;
import com.pg.config.ConfigDtos.CategoryDto;
import com.pg.config.ConfigDtos.PropertyTypeDetail;
import com.pg.config.ConfigDtos.PropertyTypeDto;
import com.pg.config.ConfigDtos.TypeAmenityDto;
import com.pg.config.ConfigDtos.TypeFieldDto;

/** Read-only configuration catalog; same for everyone who is signed in. */
@RestController
@RequestMapping("/api/config")
public class ConfigController {

	private final ConfigCatalogService catalog;

	public ConfigController(ConfigCatalogService catalog) {
		this.catalog = catalog;
	}

	@GetMapping("/property-types")
	public List<PropertyTypeDto> propertyTypes() {
		return catalog.propertyTypes();
	}

	@GetMapping("/property-types/{typeCode}")
	public PropertyTypeDetail propertyType(@PathVariable String typeCode) {
		return catalog.propertyType(typeCode);
	}

	@GetMapping("/property-types/{typeCode}/amenities")
	public List<TypeAmenityDto> typeAmenities(@PathVariable String typeCode) {
		return catalog.amenitiesOf(typeCode);
	}

	@GetMapping("/property-types/{typeCode}/fields")
	public List<TypeFieldDto> typeFields(@PathVariable String typeCode) {
		return catalog.fieldsOf(typeCode);
	}

	@GetMapping("/amenity-categories")
	public List<CategoryDto> categories() {
		return catalog.categories();
	}

	@GetMapping("/amenities")
	public List<AmenityDto> amenities() {
		return catalog.amenities();
	}
}
