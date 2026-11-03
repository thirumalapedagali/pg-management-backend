package com.pg.dashboard;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.pg.common.AppClock;
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
import com.pg.tenant.TenantContext;

/** Numbers behind the Dashboard, Today and Overview screens. */
@Service
public class DashboardService {

	public record MonthPoint(String month, BigDecimal collected, BigDecimal spent) {
	}

	public record DueItem(Long residentId, String name, String roomNumber, BigDecimal due) {
	}

	public record Summary(PeriodTotals collections, PeriodTotals expenses, PeriodTotals net, int rooms, int beds,
			int occupied, int vacant, int residents, BigDecimal totalDue, List<MonthPoint> months,
			List<DueItem> pendingDues, int checkInsToday, int checkInsMonth) {
	}

	public record TodayCheckIn(Long residentId, String name, String roomNumber, String bedNumber) {
	}

	public record TodayPayment(Long id, Long residentId, String residentName, String roomNumber, String bedNumber,
			BigDecimal amount) {
	}

	public record TodayExpense(Long id, String title, String category, BigDecimal amount) {
	}

	public record Today(LocalDate date, BigDecimal collected, BigDecimal spent, BigDecimal net,
			List<TodayPayment> payments, List<TodayExpense> expenses, List<TodayCheckIn> checkIns) {
	}

	private final PaymentRepository payments;
	private final ExpenseRepository expenses;
	private final RoomRepository rooms;
	private final ResidentRepository residents;
	private final ResidentService residentService;
	private final BedRepository beds;
	private final AppClock clock;

	public DashboardService(PaymentRepository payments, ExpenseRepository expenses, RoomRepository rooms,
			ResidentRepository residents, ResidentService residentService, BedRepository beds, AppClock clock) {
		this.beds = beds;
		this.payments = payments;
		this.expenses = expenses;
		this.rooms = rooms;
		this.residents = residents;
		this.residentService = residentService;
		this.clock = clock;
	}

	public Summary summary() {
		Long pgId = TenantContext.requirePgId();
		LocalDate today = clock.today();

		PeriodTotals collections = PeriodTotals.compute(today, (a, b) -> payments.sumBetween(pgId, a, b),
				() -> payments.sumAll(pgId));
		PeriodTotals spent = PeriodTotals.compute(today, (a, b) -> expenses.sumBetween(pgId, a, b),
				() -> expenses.sumAll(pgId));

		List<Room> roomList = rooms.findByPgIdOrderByNumber(pgId);
		int beds = roomList.stream().mapToInt(Room::getCapacity).sum();
		List<ResidentResponse> residentList = residentService.list();
		int occupied = residentList.size();
		List<DueItem> pending = residentList.stream().filter(r -> r.due().signum() > 0)
				.sorted(Comparator.comparing(ResidentResponse::due).reversed())
				.map(r -> new DueItem(r.id(), r.name(), r.roomNumber(), r.due())).toList();
		BigDecimal totalDue = pending.stream().map(DueItem::due).reduce(BigDecimal.ZERO, BigDecimal::add);

		List<MonthPoint> months = new ArrayList<>();
		YearMonth current = YearMonth.from(today);
		for (int i = 5; i >= 0; i--) {
			YearMonth ym = current.minusMonths(i);
			LocalDate from = ym.atDay(1);
			LocalDate to = ym.atEndOfMonth();
			months.add(new MonthPoint(ym.toString(), PeriodTotals.nz(payments.sumBetween(pgId, from, to)),
					PeriodTotals.nz(expenses.sumBetween(pgId, from, to))));
		}

		YearMonth thisMonth = YearMonth.from(today);
		List<LocalDate> joinDates = residents.findByPgIdOrderByName(pgId).stream().map(r -> r.getJoinedOn())
				.filter(d -> d != null).toList();
		int checkInsToday = (int) joinDates.stream().filter(today::equals).count();
		int checkInsMonth = (int) joinDates.stream().filter(d -> YearMonth.from(d).equals(thisMonth)).count();

		return new Summary(collections, spent, collections.minus(spent), roomList.size(), beds, occupied,
				Math.max(0, beds - occupied), occupied, totalDue, months, pending, checkInsToday, checkInsMonth);
	}

	public Today today() {
		Long pgId = TenantContext.requirePgId();
		LocalDate today = clock.today();
		Map<Long, com.pg.resident.Resident> byId = residents.findByPgIdOrderByName(pgId).stream()
				.collect(Collectors.toMap(r -> r.getId(), Function.identity(), (a, b) -> a));
		Map<Long, String> roomNumbers = rooms.findByPgIdOrderByNumber(pgId).stream()
				.collect(Collectors.toMap(Room::getId, Room::getNumber, (a, b) -> a));
		Map<Long, String> bedNumbers = beds.findByPgIdOrderByBedNumber(pgId).stream()
				.collect(Collectors.toMap(Bed::getId, Bed::getBedNumber, (a, b) -> a));
		List<TodayPayment> pay = payments.findByPgIdAndDateBetweenOrderByDateDescIdDesc(pgId, today, today).stream()
				.map(p -> {
					com.pg.resident.Resident r = byId.get(p.getResidentId());
					return new TodayPayment(p.getId(), p.getResidentId(), r == null ? "Removed resident" : r.getName(),
							r == null ? null : roomNumbers.get(r.getRoomId()),
							r == null || r.getBedId() == null ? null : bedNumbers.get(r.getBedId()), p.getAmount());
				})
				.toList();
		List<TodayExpense> exp = expenses.findAll((root, q, cb) -> cb.and(cb.equal(root.get("pgId"), pgId),
				cb.equal(root.get("date"), today)), PageRequest.of(0, 200, org.springframework.data.domain.Sort.by("id").descending()))
				.stream().map(e -> new TodayExpense(e.getId(), e.getTitle(), e.getCategory(), e.getAmount())).toList();
		BigDecimal collected = pay.stream().map(TodayPayment::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
		BigDecimal spent = exp.stream().map(TodayExpense::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
		List<TodayCheckIn> checkIns = byId.values().stream().filter(r -> today.equals(r.getJoinedOn()))
				.map(r -> new TodayCheckIn(r.getId(), r.getName(), roomNumbers.get(r.getRoomId()),
						r.getBedId() == null ? null : bedNumbers.get(r.getBedId())))
				.toList();
		return new Today(today, collected, spent, collected.subtract(spent), pay, exp, checkIns);
	}
}
