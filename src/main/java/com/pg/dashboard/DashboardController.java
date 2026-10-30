package com.pg.dashboard;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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

	/** Overview + Dashboard: Today/Month/Year/All-time collections, expenses, net, occupancy, dues. */
	@GetMapping("/summary")
	public Summary summary() {
		return service.summary();
	}

	@GetMapping("/today")
	public Today today() {
		return service.today();
	}
}
