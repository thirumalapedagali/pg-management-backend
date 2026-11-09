package com.pg.location;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public (no login): a resident searches before registering. */
@RestController
@RequestMapping("/api/locations")
public class LocationController {

	private final GeocodingService geocoding;

	public LocationController(GeocodingService geocoding) {
		this.geocoding = geocoding;
	}

	/** Autocomplete for a place name: up to five matches with coordinates, best first. */
	@GetMapping("/suggestions")
	public List<GeoLocation> suggestions(@RequestParam(required = false) String query) {
		return geocoding.suggest(query);
	}
}
