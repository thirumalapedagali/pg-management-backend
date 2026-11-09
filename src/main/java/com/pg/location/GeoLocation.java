package com.pg.location;

/** A place resolved by a geocoding provider. Optional parts are null when the provider does not know them. */
public record GeoLocation(String formattedAddress, double latitude, double longitude, String locality,
		String subLocality, String city, String state, String pincode, String country) {
}
