package com.pg.dashboard;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.pg.common.AppClock;
import com.pg.common.BadRequestException;
import com.pg.common.ForbiddenException;
import com.pg.common.PeriodTotals;
import com.pg.expense.ExpenseRepository;
import com.pg.payment.PaymentRepository;
import com.pg.resident.ResidentService;
import com.pg.resident.ResidentService.ResidentResponse;
import com.pg.resident.ResidentRepository;
import com.pg.room.Bed;
import com.pg.room.BedRepository;
import com.pg.room.Room;
import com.pg.room.RoomRepository;
import com.pg.room.RoomSection;
import com.pg.room.RoomSectionRepository;
import com.pg.pg.OwnerPgAccess;
import com.pg.pg.Pg;
import com.pg.pg.PgRepository;
import com.pg.tenant.TenantContext;
import com.pg.user.Role;
import com.pg.user.UserEntity;
import com.pg.user.UserRepository;

/** Numbers behind the Dashboard, Today and Overview screens. */
@Service
public class DashboardService {

	public record MonthPoint(String month, BigDecimal collected, BigDecimal spent) {
	}

	public record DueItem(Long residentId, String name, String roomNumber, String sectionName, String bedNumber, BigDecimal due,
			LocalDate joinedOn, LocalDate lastPaymentDate, String floorName) {
	}

	/** One property's share of a consolidated ("all properties") summary. */
	public record PropertySummary(Long pgId, String name, String city, int rooms, int beds, int occupied, int vacant,
			BigDecimal collectedMonth, BigDecimal spentMonth, BigDecimal totalDue, BigDecimal totalRefundableDeposit) {
	}

	/** {@code properties} is only filled for the consolidated view. */
	public record Summary(PeriodTotals collections, PeriodTotals expenses, PeriodTotals net, int rooms, int beds,
			int occupied, int vacant, int residents, BigDecimal totalDue, BigDecimal totalRefundableDeposit, List<MonthPoint> months,
			List<DueItem> pendingDues, int checkInsToday, int checkInsMonth, List<PropertySummary> properties) {
	}

	public record TodayCheckIn(Long residentId, String name, String roomNumber, String sectionName, String bedNumber, Long pgId,
			String pgName) {
	}

	public record TodayPayment(Long id, Long residentId, String residentName, String roomNumber, String sectionName, String bedNumber,
			BigDecimal amount, Long pgId, String pgName) {
	}

	public record TodayExpense(Long id, String title, String category, BigDecimal amount, Long pgId, String pgName) {
	}

	public record Today(LocalDate date, BigDecimal collected, BigDecimal spent, BigDecimal net,
			List<TodayPayment> payments, List<TodayExpense> expenses, List<TodayCheckIn> checkIns) {
	}

	private final DashboardCache cache;
	private final PaymentRepository payments;
	private final ExpenseRepository expenses;
	private final RoomRepository rooms;
	private final ResidentRepository residents;
	private final ResidentService residentService;
	private final BedRepository beds;
	private final RoomSectionRepository sections;
	private final AppClock clock;
	private final PgRepository pgs;
	private final UserRepository users;
	private final OwnerPgAccess access;

	public DashboardService(PaymentRepository payments, ExpenseRepository expenses, RoomRepository rooms,
			ResidentRepository residents, ResidentService residentService, BedRepository beds, RoomSectionRepository sections, AppClock clock,
			PgRepository pgs, UserRepository users, OwnerPgAccess access, DashboardCache cache) {
		this.cache = cache;
		this.pgs = pgs;
		this.users = users;
		this.access = access;
		this.beds = beds;
		this.sections = sections;
		this.payments = payments;
		this.expenses = expenses;
		this.rooms = rooms;
		this.residents = residents;
		this.residentService = residentService;
		this.clock = clock;
	}

	/** The signed-in owner's properties that can currently be used (licence valid). Cached briefly. */
	private List<Pg> ownedProperties(String username) {
		return cache.owned(username, () -> {
			UserEntity user = users.findByUsername(username).orElseThrow(() -> new ForbiddenException("Unknown user"));
			if (user.getRole() != Role.OWNER) {
				throw new ForbiddenException("Only an owner can view all properties");
			}
			List<Pg> list = pgs.findAllById(access.ownedPgIds(user)).stream().filter(Pg::isLicenseValid)
					.sorted(Comparator.comparing(Pg::getName, String.CASE_INSENSITIVE_ORDER)).toList();
			if (list.isEmpty()) {
				throw new BadRequestException("No properties available");
			}
			return list;
		});
	}

	public Summary summary() {
		return summaryFor(TenantContext.requirePgId());
	}

	public List<DueItem> pendingDues() {
		return pendingDuesFor(TenantContext.requirePgId());
	}

	public List<DueItem> pendingDuesAll(String username) {
		List<Pg> owned = ownedProperties(username);
		List<List<DueItem>> parts = cache.parallelMap(owned, p -> pendingDuesFor(p.getId()));
		return parts.stream().flatMap(List::stream)
				.sorted(Comparator.comparing(DueItem::due).reversed()).toList();
	}

	private List<DueItem> pendingDuesFor(Long pgId) {
		LocalDate today = clock.today();
		return cache.pg("pending-dues", pgId, today, () -> computePendingDues(pgId, today));
	}

	private List<DueItem> computePendingDues(Long pgId, LocalDate today) {
		List<ResidentResponse> residentList = residentService.list(pgId, "ACTIVE");
		Map<Long, String> roomFloors = rooms.findByPgIdOrderByNumber(pgId).stream()
				.collect(Collectors.toMap(Room::getId, rm -> rm.getFloorName() != null ? rm.getFloorName() : "Ground Floor", (a, b) -> a));
		Map<Long, LocalDate> lastPayments = payments.maxPaymentDateByResident(pgId).stream()
				.collect(Collectors.toMap(row -> (Long) row[0], row -> (LocalDate) row[1], (a, b) -> a));
		return residentList.stream().filter(r -> r.due().signum() > 0)
				.sorted(Comparator.comparing(ResidentResponse::due).reversed())
				.map(r -> {
					LocalDate lastPaymentDate = lastPayments.get(r.id());
					String floorName = r.roomId() != null ? roomFloors.getOrDefault(r.roomId(), "Ground Floor") : "Ground Floor";
					return new DueItem(r.id(), r.name(), r.roomNumber(), r.sectionName(), r.bedNumber(), r.due(), r.joinedOn(), lastPaymentDate, floorName);
				}).toList();
	}

	/** Consolidated numbers across every property of the owner. */
	public Summary summaryAll(String username) {
		List<Pg> owned = ownedProperties(username);
		List<Summary> parts = cache.parallelMap(owned, p -> summaryFor(p.getId()));

		PeriodTotals collections = parts.stream().map(Summary::collections).reduce(PeriodTotals::plus).orElseThrow();
		PeriodTotals spent = parts.stream().map(Summary::expenses).reduce(PeriodTotals::plus).orElseThrow();
		List<MonthPoint> months = new ArrayList<>();
		for (int i = 0; i < parts.get(0).months().size(); i++) {
			int idx = i;
			months.add(new MonthPoint(parts.get(0).months().get(idx).month(),
					parts.stream().map(p -> p.months().get(idx).collected()).reduce(BigDecimal.ZERO, BigDecimal::add),
					parts.stream().map(p -> p.months().get(idx).spent()).reduce(BigDecimal.ZERO, BigDecimal::add)));
		}
		List<DueItem> pending = parts.stream().flatMap(p -> p.pendingDues().stream())
				.sorted(Comparator.comparing(DueItem::due).reversed()).toList();
		List<PropertySummary> properties = new ArrayList<>();
		for (int i = 0; i < owned.size(); i++) {
			Pg pg = owned.get(i);
			Summary p = parts.get(i);
			properties.add(new PropertySummary(pg.getId(), pg.getName(), pg.getCity(), p.rooms(), p.beds(), p.occupied(),
					p.vacant(), p.collections().month(), p.expenses().month(), p.totalDue(), p.totalRefundableDeposit()));
		}
		int occupied = parts.stream().mapToInt(Summary::occupied).sum();
		BigDecimal totalRefundableDeposit = parts.stream().map(Summary::totalRefundableDeposit).reduce(BigDecimal.ZERO, BigDecimal::add);
		return new Summary(collections, spent, collections.minus(spent), parts.stream().mapToInt(Summary::rooms).sum(),
				parts.stream().mapToInt(Summary::beds).sum(), occupied, parts.stream().mapToInt(Summary::vacant).sum(),
				occupied, pending.stream().map(DueItem::due).reduce(BigDecimal.ZERO, BigDecimal::add), totalRefundableDeposit, months, pending,
				parts.stream().mapToInt(Summary::checkInsToday).sum(),
				parts.stream().mapToInt(Summary::checkInsMonth).sum(), properties);
	}

	private static Map<LocalDate, BigDecimal> byDay(List<Object[]> rows) {
		Map<LocalDate, BigDecimal> map = new HashMap<>();
		for (Object[] row : rows) {
			map.merge((LocalDate) row[0], (BigDecimal) row[1], BigDecimal::add);
		}
		return map;
	}

	private static BigDecimal sumDays(Map<LocalDate, BigDecimal> days, LocalDate from, LocalDate to) {
		BigDecimal total = BigDecimal.ZERO;
		for (Map.Entry<LocalDate, BigDecimal> e : days.entrySet()) {
			if (!e.getKey().isBefore(from) && !e.getKey().isAfter(to)) {
				total = total.add(e.getValue());
			}
		}
		return total;
	}

	/**
	 * Runs on request threads and on dashboard workers, so it takes the PG explicitly (no tenant context) and
	 * uses a handful of aggregate queries, because every round trip to the remote database is expensive.
	 */
	private Summary summaryFor(Long pgId) {
		LocalDate today = clock.today();
		return cache.pg("summary", pgId, today, () -> computeSummary(pgId, today));
	}

	private Summary computeSummary(Long pgId, LocalDate today) {
		YearMonth current = YearMonth.from(today);
		LocalDate since = current.minusMonths(5).atDay(1);
		if (today.withDayOfYear(1).isBefore(since)) {
			since = today.withDayOfYear(1);
		}
		Map<LocalDate, BigDecimal> paid = byDay(payments.dailySums(pgId, since, today));
		Map<LocalDate, BigDecimal> spentDays = byDay(expenses.dailySums(pgId, since, today));

		PeriodTotals collections = PeriodTotals.compute(today, (a, b) -> sumDays(paid, a, b),
				() -> payments.sumAll(pgId));
		PeriodTotals spent = PeriodTotals.compute(today, (a, b) -> sumDays(spentDays, a, b),
				() -> expenses.sumAll(pgId));

		Object[] roomStats = rooms.stats(pgId).get(0);
		int roomCount = ((Number) roomStats[0]).intValue();
		int beds = ((Number) roomStats[1]).intValue();
		List<ResidentResponse> residentList = residentService.list(pgId, "ACTIVE");
		int occupied = residentList.size();
		List<DueItem> pending = residentList.stream().filter(r -> r.due().signum() > 0)
				.sorted(Comparator.comparing(ResidentResponse::due).reversed())
				.map(r -> {
					return new DueItem(r.id(), r.name(), r.roomNumber(), r.sectionName(), r.bedNumber(), r.due(), r.joinedOn(), null, null);
				}).toList();
		BigDecimal totalDue = pending.stream().map(DueItem::due).reduce(BigDecimal.ZERO, BigDecimal::add);

		List<MonthPoint> months = new ArrayList<>();
		for (int i = 5; i >= 0; i--) {
			YearMonth ym = current.minusMonths(i);
			LocalDate from = ym.atDay(1);
			LocalDate to = ym.atEndOfMonth();
			months.add(new MonthPoint(ym.toString(), sumDays(paid, from, to), sumDays(spentDays, from, to)));
		}

		List<LocalDate> joinDates = residents.joinDatesSince(pgId, current.atDay(1));
		int checkInsToday = (int) joinDates.stream().filter(today::equals).count();
		int checkInsMonth = (int) joinDates.stream().filter(d -> YearMonth.from(d).equals(current)).count();

		BigDecimal totalRefundableDeposit = residents.totalRefundableDeposit(pgId);

		return new Summary(collections, spent, collections.minus(spent), roomCount, beds, occupied,
				Math.max(0, beds - occupied), occupied, totalDue, totalRefundableDeposit, months, pending, checkInsToday, checkInsMonth, null);
	}

	public Today today() {
		Long pgId = TenantContext.requirePgId();
		return todayFor(pgId, pgs.findById(pgId).map(Pg::getName).orElse(null));
	}

	/** Today's activity across every property of the owner, each row tagged with its property. */
	public Today todayAll(String username) {
		List<Pg> owned = ownedProperties(username);
		List<Today> parts = cache.parallelMap(owned, p -> todayFor(p.getId(), p.getName()));
		List<TodayPayment> pay = parts.stream().flatMap(t -> t.payments().stream()).toList();
		List<TodayExpense> exp = parts.stream().flatMap(t -> t.expenses().stream()).toList();
		List<TodayCheckIn> checkIns = parts.stream().flatMap(t -> t.checkIns().stream()).toList();
		BigDecimal collected = pay.stream().map(TodayPayment::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
		BigDecimal spent = exp.stream().map(TodayExpense::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
		return new Today(clock.today(), collected, spent, collected.subtract(spent), pay, exp, checkIns);
	}

	private Today todayFor(Long pgId, String pgName) {
		LocalDate today = clock.today();
		return cache.pg("today", pgId, today, () -> computeToday(pgId, pgName, today));
	}

	private Today computeToday(Long pgId, String pgName, LocalDate today) {

		Map<Long, com.pg.resident.Resident> byId = residents.findByPgIdOrderByName(pgId).stream()
				.collect(Collectors.toMap(r -> r.getId(), Function.identity(), (a, b) -> a));
		Map<Long, String> roomNumbers = rooms.findByPgIdOrderByNumber(pgId).stream()
				.collect(Collectors.toMap(Room::getId, Room::getNumber, (a, b) -> a));
		Map<Long, String> sectionNames = sections.findByPgIdOrderBySectionName(pgId).stream()
				.collect(Collectors.toMap(RoomSection::getId, RoomSection::getSectionName, (a, b) -> a));
		Map<Long, String> bedNumbers = beds.findByPgIdOrderByBedNumber(pgId).stream()
				.collect(Collectors.toMap(Bed::getId, Bed::getBedNumber, (a, b) -> a));
		List<TodayPayment> pay = payments.findByPgIdAndDateBetweenOrderByDateDescIdDesc(pgId, today, today).stream()
				.map(p -> {
					com.pg.resident.Resident r = byId.get(p.getResidentId());
					return new TodayPayment(p.getId(), p.getResidentId(), r == null ? "Removed resident" : r.getName(),
							r == null ? null : roomNumbers.get(r.getRoomId()),
							r == null || r.getSectionId() == null ? null : sectionNames.get(r.getSectionId()),
							r == null || r.getBedId() == null ? null : bedNumbers.get(r.getBedId()), p.getAmount(),
							pgId, pgName);
				})
				.toList();
		List<TodayExpense> exp = expenses.findAll((root, q, cb) -> cb.and(cb.equal(root.get("pgId"), pgId),
				cb.equal(root.get("date"), today)), PageRequest.of(0, 200, org.springframework.data.domain.Sort.by("id").descending()))
				.stream().map(e -> new TodayExpense(e.getId(), e.getTitle(), e.getCategory(), e.getAmount(), pgId, pgName))
				.toList();
		BigDecimal collected = pay.stream().map(TodayPayment::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
		BigDecimal spent = exp.stream().map(TodayExpense::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
		List<TodayCheckIn> checkIns = byId.values().stream().filter(r -> today.equals(r.getJoinedOn()))
				.map(r -> new TodayCheckIn(r.getId(), r.getName(), roomNumbers.get(r.getRoomId()),
						r.getSectionId() == null ? null : sectionNames.get(r.getSectionId()),
						r.getBedId() == null ? null : bedNumbers.get(r.getBedId()), pgId, pgName))
				.toList();
		return new Today(today, collected, spent, collected.subtract(spent), pay, exp, checkIns);
	}
}

