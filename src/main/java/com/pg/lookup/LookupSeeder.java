package com.pg.lookup;

import java.util.List;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Fills the lookup table when it is empty. {@code import.sql} does the same on a freshly created schema, but
 * Hibernate skips it when it only updates an existing database (ddl-auto=update), so this covers that case.
 * Each category is seeded on its own, so a category added later also reaches databases that already have data.
 */
@Component
@Order(1)
public class LookupSeeder implements ApplicationRunner {

	private final LookupValueRepository repo;

	public LookupSeeder(LookupValueRepository repo) {
		this.repo = repo;
	}

	@Override
	public void run(ApplicationArguments args) {
		seedIfMissing(LookupService.STAY_TYPE, List.of(
				new LookupValue("STAY_TYPE", "APARTMENT", "Apartment", "Flat", 1),
				new LookupValue("STAY_TYPE", "PG", "PG", "Room", 2),
				new LookupValue("STAY_TYPE", "HOSTEL", "Hostel", "Dorm", 3),
				new LookupValue("STAY_TYPE", "COLIVING", "Co-Living", "Room", 4),
				new LookupValue("STAY_TYPE", "STUDIO", "Studio", "Room", 5)));
		seedIfMissing(LookupService.GENDER_POLICY, List.of(
				new LookupValue("GENDER_POLICY", "CO_LIVING", "Co-Living / Unisex", null, 1),
				new LookupValue("GENDER_POLICY", "GENTS", "Gents", null, 2),
				new LookupValue("GENDER_POLICY", "LADIES", "Ladies", null, 3)));
		seedIfMissing(LookupService.COMPLAINT_CATEGORY, List.of(
				new LookupValue("COMPLAINT_CATEGORY", "PLUMBING", "Plumbing", null, 1),
				new LookupValue("COMPLAINT_CATEGORY", "ELECTRICAL", "Electrical", null, 2),
				new LookupValue("COMPLAINT_CATEGORY", "WIFI", "Wi-Fi", null, 3),
				new LookupValue("COMPLAINT_CATEGORY", "CLEANING", "Cleaning", null, 4),
				new LookupValue("COMPLAINT_CATEGORY", "FOOD", "Food", null, 5),
				new LookupValue("COMPLAINT_CATEGORY", "AC", "Air Conditioning", null, 6),
				new LookupValue("COMPLAINT_CATEGORY", "FURNITURE", "Room Furniture", null, 7),
				new LookupValue("COMPLAINT_CATEGORY", "OTHER", "Other", null, 8)));
	}

	private void seedIfMissing(String category, List<LookupValue> values) {
		if (repo.countByCategory(category) == 0) {
			repo.saveAll(values);
		}
	}
}
