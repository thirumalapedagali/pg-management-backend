package com.pg.common;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/** Today / this month / this year / all-time totals, as the app's Overview shows them. */
public record PeriodTotals(BigDecimal today, BigDecimal month, BigDecimal year, BigDecimal total) {

	/**
	 * @param between inclusive date-range sum (may return null for "nothing")
	 * @param all     all-time sum (may return null)
	 */
	public static PeriodTotals compute(LocalDate today, BiFunction<LocalDate, LocalDate, BigDecimal> between,
			Supplier<BigDecimal> all) {
		return new PeriodTotals(nz(between.apply(today, today)), nz(between.apply(today.withDayOfMonth(1), today)),
				nz(between.apply(today.withDayOfYear(1), today)), nz(all.get()));
	}

	public PeriodTotals plus(PeriodTotals o) {
		return new PeriodTotals(today.add(o.today), month.add(o.month), year.add(o.year), total.add(o.total));
	}

	public PeriodTotals minus(PeriodTotals o) {
		return new PeriodTotals(today.subtract(o.today), month.subtract(o.month), year.subtract(o.year),
				total.subtract(o.total));
	}

	public static BigDecimal nz(BigDecimal v) {
		return v == null ? BigDecimal.ZERO : v;
	}
}
