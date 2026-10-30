package com.pg.billing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import org.springframework.stereotype.Component;

import com.pg.common.AppClock;
import com.pg.resident.Resident;
import com.pg.room.Room;

/**
 * Billing rule (identical to the app): a resident owes the room's rent for every calendar month from the
 * join month through the current month, inclusive. due = max(0, billed - paid).
 */
@Component
public class Billing {

	private final AppClock clock;

	public Billing(AppClock clock) {
		this.clock = clock;
	}

	public int monthsBilled(LocalDate joinedOn) {
		LocalDate today = clock.today();
		long months = ChronoUnit.MONTHS.between(joinedOn.withDayOfMonth(1), today.withDayOfMonth(1)) + 1;
		return (int) Math.max(0, months);
	}

	public BigDecimal billed(Resident r, Room room) {
		return room.getRent().multiply(BigDecimal.valueOf(monthsBilled(r.getJoinedOn())));
	}

	public static BigDecimal due(BigDecimal billed, BigDecimal paid) {
		return billed.subtract(paid).max(BigDecimal.ZERO);
	}
}
