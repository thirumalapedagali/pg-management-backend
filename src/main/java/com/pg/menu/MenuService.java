package com.pg.menu;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pg.common.AppClock;
import com.pg.common.BadRequestException;
import com.pg.common.NotFoundException;
import com.pg.tenant.TenantContext;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * The property's weekly food menu. Meals belong to a weekday, so the same week repeats every week until the owner
 * changes it. Residents say which upcoming meals they will eat, so the kitchen knows how much to cook.
 */
@Service
public class MenuService {

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("H:mm");
	private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

	/** {@code days} (1 = Monday ... 7 = Sunday) is only read when adding; an edit changes the one meal. */
	public record MealRequest(@NotBlank @Size(max = 40) String name, @NotBlank String startTime,
			@NotBlank String endTime, @NotBlank @Size(max = 1500) String items, List<Integer> days) {
	}

	/** One meal of a day. No {@code items} means it is not served that day. */
	public record DayMeal(@NotBlank @Size(max = 40) String name, String startTime, String endTime,
			@Size(max = 1500) String items) {
	}

	/** Saves {@code meals} on every one of {@code days} (1 = Monday ... 7 = Sunday). */
	public record DayRequest(@NotEmpty List<Integer> days, @NotEmpty @Valid List<DayMeal> meals) {
	}


	public record MealResponse(Long id, int dayOfWeek, String name, String startTime, String endTime,
			List<String> items) {
	}

	/** One meal on one date as a resident sees it. {@code state} is NOW (being served) or UPCOMING. */
	public record MealOccurrence(Long mealId, String name, LocalDate date, String startTime, String endTime,
			List<String> items, String state, boolean attending, boolean canRespond) {
	}

	public record AttendanceCount(Long mealId, long count) {
	}

	private final MenuMealRepository meals;
	private final MealAttendanceRepository attendance;
	private final AppClock clock;

	public MenuService(MenuMealRepository meals, MealAttendanceRepository attendance, AppClock clock) {
		this.meals = meals;
		this.attendance = attendance;
		this.clock = clock;
	}

	/** The whole week, in weekday then time order. */
	@Transactional(readOnly = true)
	public List<MealResponse> list() {
		Long pgId = TenantContext.requirePgId();
		return meals.findByPgIdOrderByDayOfWeekAscStartTimeAsc(pgId).stream().map(MenuService::toResponse).toList();
	}

	@Transactional
	public List<MealResponse> add(MealRequest req) {
		Long pgId = TenantContext.requirePgId();
		Set<Integer> days = new LinkedHashSet<>(req.days() == null ? List.of() : req.days());
		if (days.isEmpty()) {
			throw new BadRequestException("Choose at least one day");
		}
		List<MealResponse> created = new ArrayList<>();
		for (int day : days) {
			if (day < 1 || day > 7) {
				throw new BadRequestException("Invalid day: " + day);
			}
			MenuMeal m = new MenuMeal();
			m.setDayOfWeek(day);
			apply(m, req);
			created.add(toResponse(meals.save(m)));
		}
		return created;
	}

	@Transactional
	public MealResponse update(Long id, MealRequest req) {
		MenuMeal m = find(id);
		apply(m, req);
		return toResponse(meals.save(m));
	}

	/**
	 * Saves a whole day in one go: each meal in the request is matched by name on every chosen day, updated if it
	 * exists and created if not. A meal with no food items is "not served" and is removed. Other meals are untouched.
	 */
	@Transactional
	public List<MealResponse> saveDay(DayRequest req) {
		Long pgId = TenantContext.requirePgId();
		Set<Integer> days = new LinkedHashSet<>(req.days());
		for (int day : days) {
			if (day < 1 || day > 7) {
				throw new BadRequestException("Invalid day: " + day);
			}
			List<MenuMeal> existing = meals.findByPgIdAndDayOfWeekOrderByStartTimeAsc(pgId, day);
			for (DayMeal dm : req.meals()) {
				String name = dm.name().trim();
				MenuMeal match = existing.stream().filter(m -> m.getName().equalsIgnoreCase(name)).findFirst()
						.orElse(null);
				if (dm.items() == null || dm.items().isBlank()) {
					if (match != null) {
						attendance.deleteByPgIdAndMealId(pgId, match.getId());
						meals.delete(match);
					}
					continue;
				}
				MenuMeal m = match != null ? match : new MenuMeal();
				m.setDayOfWeek(day);
				apply(m, new MealRequest(name, dm.startTime(), dm.endTime(), dm.items(), null));
				meals.save(m);
			}
		}
		return list();
	}


	@Transactional
	public void delete(Long id) {
		MenuMeal m = find(id);
		attendance.deleteByPgIdAndMealId(TenantContext.requirePgId(), m.getId());
		meals.delete(m);
	}

	/** How many residents said they will eat each meal on {@code date} (today when null). */
	@Transactional(readOnly = true)
	public List<AttendanceCount> counts(LocalDate date) {
		Long pgId = TenantContext.requirePgId();
		return attendance.countByMeal(pgId, date != null ? date : clock.today()).stream()
				.map(r -> new AttendanceCount((Long) r[0], (Long) r[2])).toList();
	}

	/** Meals being served now, the rest of today's, and all of tomorrow's, with this resident's answer on each. */
	@Transactional(readOnly = true)
	public List<MealOccurrence> upcomingFor(Long residentId) {
		Long pgId = TenantContext.requirePgId();
		LocalDate today = clock.today();
		LocalTime now = clock.now().toLocalTime();
		List<LocalDate> dates = List.of(today, today.plusDays(1));
		Set<String> mine = new HashSet<>();
		for (MealAttendance a : attendance.findByPgIdAndResidentIdAndMealDateIn(pgId, residentId, dates)) {
			mine.add(a.getMealId() + "@" + a.getMealDate());
		}
		List<MealOccurrence> out = new ArrayList<>();
		for (LocalDate date : dates) {
			for (MenuMeal m : meals.findByPgIdAndDayOfWeekOrderByStartTimeAsc(pgId, date.getDayOfWeek().getValue())) {
				boolean isToday = date.equals(today);
				if (isToday && !m.getEndTime().isAfter(now)) {
					continue;
				}
				boolean started = isToday && !m.getStartTime().isAfter(now);
				out.add(new MealOccurrence(m.getId(), m.getName(), date, m.getStartTime().format(HH_MM),
						m.getEndTime().format(HH_MM), splitItems(m.getItems()), started ? "NOW" : "UPCOMING",
						mine.contains(m.getId() + "@" + date), !started));
			}
		}
		return out;
	}

	/** Marks or clears the resident's "I will eat this" for a meal on today or tomorrow, until it starts. */
	@Transactional
	public void setAttending(Long residentId, Long mealId, LocalDate date, boolean attending) {
		Long pgId = TenantContext.requirePgId();
		MenuMeal m = find(mealId);
		LocalDate today = clock.today();
		if (date.isBefore(today) || date.isAfter(today.plusDays(1))) {
			throw new BadRequestException("You can respond for today and tomorrow only");
		}
		if (date.getDayOfWeek().getValue() != m.getDayOfWeek()) {
			throw new BadRequestException("This meal is not served on that day");
		}
		if (date.equals(today) && !m.getStartTime().isAfter(clock.now().toLocalTime())) {
			throw new BadRequestException("This meal has already started");
		}
		var existing = attendance.findByPgIdAndResidentIdAndMealIdAndMealDate(pgId, residentId, mealId, date);
		if (attending && existing.isEmpty()) {
			MealAttendance a = new MealAttendance();
			a.setResidentId(residentId);
			a.setMealId(mealId);
			a.setMealDate(date);
			attendance.save(a);
		} else if (!attending) {
			existing.ifPresent(attendance::delete);
		}
	}

	private MenuMeal find(Long id) {
		return meals.findByPgIdAndId(TenantContext.requirePgId(), id)
				.orElseThrow(() -> new NotFoundException("Meal not found"));
	}

	private void apply(MenuMeal m, MealRequest req) {
		LocalTime start = parseTime(req.startTime());
		LocalTime end = parseTime(req.endTime());
		if (!end.isAfter(start)) {
			throw new BadRequestException("End time must be after the start time");
		}
		List<String> items = splitItems(req.items());
		if (items.isEmpty()) {
			throw new BadRequestException("Add at least one food item");
		}
		String joined = String.join("\n", items);
		if (joined.length() > 1500) {
			throw new BadRequestException("Too many food items");
		}
		m.setName(req.name().trim());
		m.setStartTime(start);
		m.setEndTime(end);
		m.setItems(joined);
	}

	private static LocalTime parseTime(String text) {
		try {
			return LocalTime.parse(text.trim(), TIME);
		} catch (DateTimeParseException e) {
			throw new BadRequestException("Enter the time as HH:mm, for example 07:30 or 19:45");
		}
	}

	/** Items are typed one per line (commas also separate them). */
	private static List<String> splitItems(String text) {
		return Arrays.stream(text.split("[\\r\\n,]+")).map(String::trim).filter(s -> !s.isEmpty()).toList();
	}

	private static MealResponse toResponse(MenuMeal m) {
		return new MealResponse(m.getId(), m.getDayOfWeek(), m.getName(), m.getStartTime().format(HH_MM),
				m.getEndTime().format(HH_MM), splitItems(m.getItems()));
	}
}
