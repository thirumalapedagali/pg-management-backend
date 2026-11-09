package com.pg.config;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pg.config.ConfigDtos.ConfigurationRequest;
import com.pg.config.ConfigDtos.PropertyConfiguration;
import com.pg.config.ConfigDtos.RoomAmenities;
import com.pg.config.ConfigDtos.RoomAmenitiesRequest;

import jakarta.validation.Valid;

/** A property's own configuration: which amenities it has and its custom field values. */
@RestController
@RequestMapping("/api/properties/{propertyId}")
public class PropertyConfigurationController {

	private final PropertyConfigurationService service;

	public PropertyConfigurationController(PropertyConfigurationService service) {
		this.service = service;
	}

	@GetMapping("/configuration")
	public PropertyConfiguration get(@PathVariable Long propertyId, Authentication auth) {
		return service.get(propertyId, auth);
	}

	@PutMapping("/configuration")
	public PropertyConfiguration save(@PathVariable Long propertyId, @Valid @RequestBody ConfigurationRequest req,
			Authentication auth) {
		return service.save(propertyId, req, auth);
	}

	@GetMapping("/rooms/{roomId}/amenities")
	public RoomAmenities room(@PathVariable Long propertyId, @PathVariable Long roomId, Authentication auth) {
		return service.getRoom(propertyId, roomId, auth);
	}

	@PutMapping("/rooms/{roomId}/amenities")
	public RoomAmenities saveRoom(@PathVariable Long propertyId, @PathVariable Long roomId,
			@Valid @RequestBody RoomAmenitiesRequest req, Authentication auth) {
		return service.saveRoom(propertyId, roomId, req, auth);
	}
}
