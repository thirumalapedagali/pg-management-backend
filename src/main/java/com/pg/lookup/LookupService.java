package com.pg.lookup;

import java.util.List;

import org.springframework.stereotype.Service;

@Service
public class LookupService {

	public static final String STAY_TYPE = "STAY_TYPE";
	public static final String GENDER_POLICY = "GENDER_POLICY";
	public static final String COMPLAINT_CATEGORY = "COMPLAINT_CATEGORY";

	public record LookupItem(String code, String label, String extra) {
	}

	private final LookupValueRepository repo;

	public LookupService(LookupValueRepository repo) {
		this.repo = repo;
	}

	public List<LookupItem> list(String category) {
		return repo.findByCategoryAndActiveTrueOrderBySortOrderAscLabelAsc(category.trim().toUpperCase()).stream()
				.map(v -> new LookupItem(v.getCode(), v.getLabel(), v.getExtra())).toList();
	}

	/** The label of an active option, or empty when the code is not (or no longer) offered. */
	public java.util.Optional<String> activeLabel(String category, String code) {
		if (code == null || code.isBlank()) {
			return java.util.Optional.empty();
		}
		return repo.findByCategoryAndCode(category, code.trim().toUpperCase()).filter(LookupValue::isActive)
				.map(LookupValue::getLabel);
	}

	/** What a unit is called for this stay type (Flat, Dorm, Room...), from the STAY_TYPE table. */
	public String defaultUnitLabel(String stayType) {
		if (stayType == null || stayType.isBlank()) {
			return "Room";
		}
		return repo.findByCategoryAndCode(STAY_TYPE, stayType.trim().toUpperCase()).map(LookupValue::getExtra)
				.filter(e -> e != null && !e.isBlank()).orElse("Room");
	}
}
