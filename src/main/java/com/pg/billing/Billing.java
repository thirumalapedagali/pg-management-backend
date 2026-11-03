package com.pg.billing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import org.springframework.stereotype.Component;

import com.pg.common.AppClock;
import com.pg.resident.Resident;
import com.pg.resident.StayType;
import com.pg.room.Room;

/**
 * Billing rules:
 * - MONTHLY: a resident owes the room's rent for each monthly billing cycle calculated
 *   directly from their joining date through the current date, inclusive.
 * - DAILY: a resident owes their configured daily rate multiplied by the number of days stayed
 *   from joining date to checkout/today.
 * due = max(0, billed - paid).
 */
@Component
public class Billing {

	private final AppClock clock;

	public Billing(AppClock clock) {
		this.clock = clock;
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
		BigDecimal rent = r.getRentOverride() != null ? r.getRentOverride()
				: (room != null ? room.getRent() : BigDecimal.ZERO);
		return rent.multiply(BigDecimal.valueOf(monthsBilled(r.getJoinedOn(), r.getCheckOutDate())));
	}

	public static BigDecimal due(BigDecimal billed, BigDecimal paid) {
		if (billed == null) return BigDecimal.ZERO;
		if (paid == null) paid = BigDecimal.ZERO;
		return billed.subtract(paid).max(BigDecimal.ZERO);
	}
}
