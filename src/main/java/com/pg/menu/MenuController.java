package com.pg.menu;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pg.menu.MenuService.AttendanceCount;
import com.pg.menu.MenuService.DayRequest;
import com.pg.menu.MenuService.MealRequest;
import com.pg.menu.MenuService.MealResponse;

import jakarta.validation.Valid;

/** The weekly food menu: staff edit it and see who is eating, residents only read it (see SecurityConfig). */
@RestController
@RequestMapping("/api/menu")
public class MenuController {

	private final MenuService service;

	public MenuController(MenuService service) {
		this.service = service;
	}

	@GetMapping
	public List<MealResponse> list() {
		return service.list();
	}

	@PostMapping
	public List<MealResponse> add(@Valid @RequestBody MealRequest req) {
		return service.add(req);
	}

	/** Saves a whole day (breakfast, lunch, dinner...) on one or more weekdays at once. */
	@PutMapping("/day")
	public List<MealResponse> saveDay(@Valid @RequestBody DayRequest req) {
		return service.saveDay(req);
	}

	@PutMapping("/{id}")
	public MealResponse update(@PathVariable Long id, @Valid @RequestBody MealRequest req) {
		return service.update(id, req);
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@PathVariable Long id) {
		service.delete(id);
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/attendance")
	public List<AttendanceCount> attendance(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
		return service.counts(date);
	}
}
