package com.pg.config;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pg.config.ConfigDtos.AmenityDto;
import com.pg.config.ConfigDtos.CreateAmenityRequest;
import com.pg.config.ConfigDtos.CreateFieldRequest;
import com.pg.config.ConfigDtos.FieldDto;
import com.pg.config.ConfigDtos.MapAmenityRequest;
import com.pg.config.ConfigDtos.MapFieldRequest;
import com.pg.config.ConfigDtos.TypeAmenityDto;
import com.pg.config.ConfigDtos.TypeFieldDto;
import com.pg.config.ConfigDtos.UpdateAmenityRequest;
import com.pg.config.ConfigDtos.UpdateFieldRequest;

import jakarta.validation.Valid;

/**
 * Super admin maintenance of the catalog (SecurityConfig restricts /api/admin/** to SUPER_ADMIN). Nothing is ever
 * deleted: a mapping is retired with {@code active=false}. New property types are added in the STAY_TYPE lookup
 * ({@code POST /api/admin/lookups}).
 */
@RestController
@RequestMapping("/api/admin")
public class AdminConfigController {

	private final ConfigCatalogService catalog;

	public AdminConfigController(ConfigCatalogService catalog) {
		this.catalog = catalog;
	}

	/** Everything, retired entries included (the public {@code /api/config/amenities} only lists active ones). */
	@GetMapping("/amenities")
	public List<AmenityDto> amenities() {
		return catalog.allAmenities();
	}

	@GetMapping("/property-fields")
	public List<FieldDto> fields() {
		return catalog.allFields();
	}

	@PutMapping("/amenities/{id}")
	public AmenityDto updateAmenity(@PathVariable Long id, @Valid @RequestBody UpdateAmenityRequest req) {
		return catalog.updateAmenity(id, req);
	}

	@PutMapping("/property-fields/{id}")
	public FieldDto updateField(@PathVariable Long id, @Valid @RequestBody UpdateFieldRequest req) {
		return catalog.updateField(id, req);
	}


	@PostMapping("/amenities")
	public AmenityDto createAmenity(@Valid @RequestBody CreateAmenityRequest req) {
		return catalog.createAmenity(req);
	}

	@PostMapping("/property-fields")
	public FieldDto createField(@Valid @RequestBody CreateFieldRequest req) {
		return catalog.createField(req);
	}

	@PostMapping("/property-types/{typeCode}/amenities")
	public TypeAmenityDto mapAmenity(@PathVariable String typeCode, @Valid @RequestBody MapAmenityRequest req) {
		return catalog.mapAmenity(typeCode, req);
	}

	@PostMapping("/property-types/{typeCode}/fields")
	public TypeFieldDto mapField(@PathVariable String typeCode, @Valid @RequestBody MapFieldRequest req) {
		return catalog.mapField(typeCode, req);
	}
}
