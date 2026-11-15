package com.pg.booking;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pg.booking.BookingRequestService.ApproveBookingRequest;
import com.pg.booking.BookingRequestService.BookingRequestResponse;
import com.pg.booking.BookingRequestService.RejectBookingRequest;
import com.pg.resident.ResidentService.ResidentResponse;

@RestController
@RequestMapping("/api/booking-requests")
public class BookingRequestController {

	private final BookingRequestService service;

	public BookingRequestController(BookingRequestService service) {
		this.service = service;
	}

	/** With {@code page} the result is one page ({@code Paged}); without it, the full list as before. */
	@GetMapping
	public ResponseEntity<?> list(@RequestParam(required = false, defaultValue = "PENDING") String status,
			@RequestParam(required = false) String search, @RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size) {
		if (page != null) {
			return ResponseEntity.ok(service.listPaged(status, search, page, size != null ? size : 20));
		}
		return ResponseEntity.ok(service.list(status));
	}

	@GetMapping("/count")
	public Map<String, Long> countPending() {
		return Map.of("pending", service.countPending());
	}

	@PostMapping("/{id}/approve")
	public ResidentResponse approve(@PathVariable Long id, @RequestBody(required = false) ApproveBookingRequest req) {
		return service.approve(id, req);
	}

	@PostMapping("/{id}/reject")
	public BookingRequestResponse reject(@PathVariable Long id, @RequestBody(required = false) RejectBookingRequest req) {
		return service.reject(id, req);
	}
}
