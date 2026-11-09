package com.pg.dashboard;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pg.dashboard.DashboardService.Summary;
import com.pg.dashboard.DashboardService.Today;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

	private final DashboardService service;

	public DashboardController(DashboardService service) {
		this.service = service;
	}

	/**
	 * Overview + Dashboard: Today/Month/Year/All-time collections, expenses, net, occupancy, dues.
	 * {@code scope=all} (owners only) consolidates every property the owner has.
	 */
	@GetMapping("/summary")
	public Summary summary(@RequestParam(required = false) String scope, Authentication auth) {
		return "all".equalsIgnoreCase(scope) ? service.summaryAll(auth.getName()) : service.summary();
	}

	@GetMapping("/today")
	public Today today(@RequestParam(required = false) String scope, Authentication auth) {
		return "all".equalsIgnoreCase(scope) ? service.todayAll(auth.getName()) : service.today();
	}
}
