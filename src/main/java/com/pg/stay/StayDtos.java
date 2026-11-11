package com.pg.stay;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.pg.location.GeoLocation;
import com.pg.room.SharingType;

/** JSON shapes of the stay search APIs. Nothing here exposes owner contact details beyond the public number, UPI or licence data. */
public final class StayDtos {

	private StayDtos() {
	}

	/** One kind of bed (e.g. "2 Sharing") a property offers, over the beds that matched the filters. */
	public record SharingOption(SharingType sharingType, String label, BigDecimal rentPerMonth,
			BigDecimal maxRentPerMonth, int availableBeds, int upcomingBeds,
			LocalDate availableFrom, BigDecimal depositPerMonth) {
	}

	public record StayCard(Long propertyId, String propertyName, String stayType, String genderType, String address,
			String locality, String subLocality, String city, String state, String pincode, Double latitude,
			Double longitude, Double distanceKm, BigDecimal startingRent, BigDecimal maxRent, int availableBeds,
			int upcomingBeds, LocalDate availableFrom, List<SharingOption> sharingOptions, List<String> amenities, List<String> images, String description,
			String contactMobile) {
	}

	/** The place the search was centred on (resolved from text, or the coordinates the client sent). */
	public record StaySearchResult(GeoLocation location, com.pg.common.Paged<StayCard> results) {
	}

	public enum SortBy {
		NEAREST, RENT_ASC, RENT_DESC;

		public static SortBy parse(String s) {
			if (s == null || s.isBlank()) {
				return NEAREST;
			}
			return switch (s.trim().toUpperCase().replace('-', '_')) {
				case "NEAREST", "DISTANCE" -> NEAREST;
				case "RENT_ASC", "LOWEST_RENT", "LOWEST", "PRICE_ASC" -> RENT_ASC;
				case "RENT_DESC", "HIGHEST_RENT", "HIGHEST", "PRICE_DESC" -> RENT_DESC;
				default -> throw new com.pg.common.BadRequestException(
						"sortBy must be NEAREST, RENT_ASC or RENT_DESC");
			};
		}
	}

	/** Validated search input. {@code gender} is MALE, FEMALE or UNISEX (or null for any). */
	public record StayFilter(double latitude, double longitude, double radiusKm, BigDecimal minRent,
			BigDecimal maxRent, SharingType sharingType, String stayType, String gender, boolean availableOnly,
			com.pg.room.AcType acType, Boolean attachedBathroom, List<String> amenities, SortBy sortBy, int page,
			int size) {
	}
}
