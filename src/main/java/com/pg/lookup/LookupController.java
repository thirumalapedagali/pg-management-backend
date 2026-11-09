package com.pg.lookup;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pg.lookup.LookupService.LookupItem;

/** Options for the app's dropdowns, e.g. {@code GET /api/lookups?category=STAY_TYPE}. */
@RestController
@RequestMapping("/api/lookups")
public class LookupController {

	private final LookupService lookups;

	public LookupController(LookupService lookups) {
		this.lookups = lookups;
	}

	@GetMapping
	public List<LookupItem> list(@RequestParam String category) {
		return lookups.list(category);
	}
}
