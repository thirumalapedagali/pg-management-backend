package com.pg.billing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.stereotype.Component;

import com.pg.common.AppClock;
import com.pg.resident.Resident;
import com.pg.resident.ResidentRentChange;
import com.pg.resident.ResidentRentChangeRepository;
import com.pg.resident.StayType;
import com.pg.room.Room;

/**
 * Billing rules:
 * - MONTHLY: a resident owes the rent for each monthly billing cycle calculated
 *   directly from their joining date through the current date, inclusive. Each cycle uses the rent in force
 *   at its start: the resident's base rent, or the latest {@link ResidentRentChange} effective on/before it.
 * - DAILY: a resident owes their configured daily rate multiplied by the number of days stayed
 *   from joining date to checkout/today.
 * due = max(0, billed - paid).
 */
@Component
public class Billing {

	private final AppClock clock;
	private final ResidentRentChangeRepository rentChanges;

	public Billing(AppClock clock, ResidentRentChangeRepository rentChanges) {
		this.clock = clock;
		this.rentChanges = rentChanges;
	}

	public int monthsBilled(LocalDate joinedOn) {
		return monthsBilled(joinedOn, null);
	}

	public int monthsBilled(LocalDate joinedOn, LocalDate checkOutDate) {
		if (joinedOn == null) {
			return 0;
		}
		LocalDate end = checkOutDate != null ? checkOutDate : clock.today();
		if (end.isBefore(joinedOn)) {
			return 0;
		}
		// Number of completed monthly cycles between joinedOn and end + 1 for current active cycle
		long completedMonths = ChronoUnit.MONTHS.between(joinedOn, end);
		return (int) (completedMonths + 1);
	}

	public int daysStayed(LocalDate joinedOn, LocalDate checkOutDate) {
		return daysStayed(null, null, joinedOn, checkOutDate);
	}

	public int daysStayed(java.time.LocalDateTime checkInTime, java.time.LocalDateTime checkOutTime,
			LocalDate joinedOn, LocalDate checkOutDate) {
		if (checkInTime != null) {
			java.time.LocalDateTime end = checkOutTime != null ? checkOutTime : clock.now();
			if (end.isBefore(checkInTime)) {
				return 1;
			}
			long totalMinutes = ChronoUnit.MINUTES.between(checkInTime, end);
			// Exact 24-hour cycle: 1 to 1440 min = 1 day, 1441 to 2880 min = 2 days, etc.
			int days = (int) Math.ceil((double) Math.max(1, totalMinutes) / (24 * 60));
			return Math.max(1, days);
		}
		if (joinedOn == null) {
			return 0;
		}
		LocalDate today = clock.today();
		if (today.isBefore(joinedOn)) {
			return 0;
		}
		LocalDate end = checkOutDate != null ? checkOutDate : today;
		if (end.isBefore(joinedOn)) {
			return 0;
		}
		long days = ChronoUnit.DAYS.between(joinedOn, end) + 1;
		return (int) Math.max(1, days);
	}

	public double hoursStayed(java.time.LocalDateTime checkInTime, java.time.LocalDateTime checkOutTime,
			LocalDate joinedOn, LocalDate checkOutDate) {
		if (checkInTime != null) {
			java.time.LocalDateTime end = checkOutTime != null ? checkOutTime : clock.now();
			long minutes = ChronoUnit.MINUTES.between(checkInTime, end);
			return Math.max(0.0, Math.round((minutes / 60.0) * 10.0) / 10.0);
		}
		int days = daysStayed(joinedOn, checkOutDate);
		return days * 24.0;
	}

	public BigDecimal billed(Resident r, Room room) {
		return billed(r, room, null);
	}

	/** {@code changes} may be null (loaded on demand) or pre-loaded, ascending by effective date. */
	public BigDecimal billed(Resident r, Room room, List<ResidentRentChange> changes) {
		if (r == null || (r.getJoinedOn() == null && r.getCheckInTime() == null)) {
			return BigDecimal.ZERO;
		}
		if (r.getStayType() == StayType.DAILY) {
			BigDecimal dailyRate = r.getDailyRate() != null && r.getDailyRate().signum() > 0 ? r.getDailyRate()
					: (r.getRentOverride() != null && r.getRentOverride().signum() > 0 ? r.getRentOverride()
					: (room != null && room.getDailyRent() != null && room.getDailyRent().signum() > 0 ? room.getDailyRent()
					: (room != null && room.getRent() != null ? room.getRent().divide(BigDecimal.valueOf(30), 2, java.math.RoundingMode.HALF_UP) : BigDecimal.ZERO)));
			int days = daysStayed(r.getCheckInTime(), r.getCheckOutTime(), r.getJoinedOn(), r.getCheckOutDate());
			return dailyRate.multiply(BigDecimal.valueOf(days));
		}
		return monthlyBilled(r, room, r.getCheckOutDate(), changes);
	}

	/** Monthly rent owed from joining up to {@code end} (null = today). */
	public BigDecimal monthlyBilled(Resident r, Room room, LocalDate end, List<ResidentRentChange> changes) {
		if (r.getJoinedOn() == null) {
			return BigDecimal.ZERO;
		}
		BigDecimal base = baseRent(r, room);
		List<ResidentRentChange> list = changes != null ? changes : changesFor(r);
		int months = monthsBilled(r.getJoinedOn(), end);
		BigDecimal total = BigDecimal.ZERO;
		for (int k = 0; k < months; k++) {
			total = total.add(rentForCycle(base, list, r.getJoinedOn().plusMonths(k)));
		}
		return total;
	}

	/** The rent the resident joined with (before any scheduled change). */
	public BigDecimal baseRent(Resident r, Room room) {
		return r.getRentOverride() != null ? r.getRentOverride() : (room != null ? room.getRent() : BigDecimal.ZERO);
	}

	/** Rent for the billing cycle starting on {@code cycleStart}; {@code changes} must be ascending by date. */
	public static BigDecimal rentForCycle(BigDecimal base, List<ResidentRentChange> changes, LocalDate cycleStart) {
		BigDecimal rent = base;
		for (ResidentRentChange c : changes) {
			if (!c.getEffectiveFrom().isAfter(cycleStart)) {
				rent = c.getRent();
			}
		}
		return rent;
	}

	/** Rent for the cycle that contains today. */
	public BigDecimal currentRent(Resident r, Room room, List<ResidentRentChange> changes) {
		BigDecimal base = baseRent(r, room);
		if (r.getJoinedOn() == null) {
			return base;
		}
		List<ResidentRentChange> list = changes != null ? changes : changesFor(r);
		LocalDate today = clock.today();
		long elapsed = today.isBefore(r.getJoinedOn()) ? 0 : ChronoUnit.MONTHS.between(r.getJoinedOn(), today);
		return rentForCycle(base, list, r.getJoinedOn().plusMonths(elapsed));
	}

	/** Start date of the resident's next billing cycle (their joining date if they have not joined yet). */
	public LocalDate nextCycleStart(Resident r) {
		LocalDate today = clock.today();
		if (r.getJoinedOn() == null || today.isBefore(r.getJoinedOn())) {
			return r.getJoinedOn() != null ? r.getJoinedOn() : today;
		}
		return r.getJoinedOn().plusMonths(ChronoUnit.MONTHS.between(r.getJoinedOn(), today) + 1);
	}

	/** Scheduled rent changes for a resident, ascending by effective date. */
	public List<ResidentRentChange> changesFor(Resident r) {
		return rentChanges.findByPgIdAndResidentIdOrderByEffectiveFrom(r.getPgId(), r.getId());
	}

	public static BigDecimal due(BigDecimal billed, BigDecimal paid) {
		if (billed == null) return BigDecimal.ZERO;
		if (paid == null) paid = BigDecimal.ZERO;
		return billed.subtract(paid).max(BigDecimal.ZERO);
	}
}
