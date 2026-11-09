package com.pg.stay;

import java.math.BigDecimal;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pg.common.Paged;
import com.pg.stay.StayDtos.StayCard;
import com.pg.stay.StayDtos.StaySearchResult;
import com.pg.stay.StayService.Query;
import com.pg.stay.StayService.StayDetail;

/** Public (no login) stay discovery by location. Booking and payment stay behind login. */
@RestController
@RequestMapping("/api/stays")
public class StayController {

	private final StayService service;

	public StayController(StayService service) {
		this.service = service;
	}

	/** Stays within {@code radiusKm} of a point, nearest first. */
	@GetMapping("/nearby")
	public Paged<StayCard> nearby(@RequestParam(required = false) Double latitude,
			@RequestParam(required = false) Double longitude, @RequestParam(required = false) Double radiusKm,
			@RequestParam(required = false) BigDecimal minRent, @RequestParam(required = false) BigDecimal maxRent,
			@RequestParam(required = false) String sharingType, @RequestParam(required = false) String stayType,
			@RequestParam(required = false) String gender, @RequestParam(required = false) Boolean availableOnly,
			@RequestParam(required = false) String acType, @RequestParam(required = false) Boolean attachedBathroom,
			@RequestParam(required = false) String amenities, @RequestParam(required = false) String sortBy,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
		return service.nearby(new Query(latitude, longitude, radiusKm, minRent, maxRent, sharingType, stayType, gender,
				availableOnly, acType, attachedBathroom, amenities, sortBy, page, size));
	}

	/**
	 * Stays around a place typed by the user. Send {@code latitude}/{@code longitude} too when a suggestion was picked
	 * (they come from /api/locations/suggestions) and the geocoder is not called at all.
	 */
	@GetMapping("/search")
	public StaySearchResult search(@RequestParam(required = false) String query,
			@RequestParam(required = false) Double latitude, @RequestParam(required = false) Double longitude,
			@RequestParam(required = false) Double radiusKm, @RequestParam(required = false) BigDecimal minRent,
			@RequestParam(required = false) BigDecimal maxRent, @RequestParam(required = false) String sharingType,
			@RequestParam(required = false) String stayType, @RequestParam(required = false) String gender,
			@RequestParam(required = false) Boolean availableOnly, @RequestParam(required = false) String acType,
			@RequestParam(required = false) Boolean attachedBathroom, @RequestParam(required = false) String amenities,
			@RequestParam(required = false) String sortBy, @RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size) {
		return service.search(query, new Query(latitude, longitude, radiusKm, minRent, maxRent, sharingType, stayType,
				gender, availableOnly, acType, attachedBathroom, amenities, sortBy, page, size));
	}

	@GetMapping("/{propertyId}")
	public StayDetail details(@PathVariable Long propertyId) {
		return service.details(propertyId);
	}
}
