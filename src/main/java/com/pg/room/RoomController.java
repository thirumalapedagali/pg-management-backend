package com.pg.room;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pg.room.RoomService.RentUpdateRequest;
import com.pg.room.RoomService.RoomRequest;
import com.pg.room.RoomService.RoomResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/rooms")
public class RoomController {

	private final RoomService service;

	public RoomController(RoomService service) {
		this.service = service;
	}

	@GetMapping
	public List<RoomResponse> list() {
		return service.list();
	}

	@PostMapping
	public RoomResponse create(@Valid @RequestBody RoomRequest req) {
		return service.create(req);
	}

	@PutMapping("/{id}")
	public RoomResponse update(@PathVariable Long id, @Valid @RequestBody RoomRequest req) {
		return service.update(id, req);
	}

	/** Rent change: new residents pay it now, current residents from their next billing cycle. */
	@PutMapping("/{id}/rent")
	public RoomResponse updateRent(@PathVariable Long id, @RequestBody RentUpdateRequest req) {
		return service.updateRent(id, req);
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@PathVariable Long id) {
		service.delete(id);
		return ResponseEntity.noContent().build();
	}
}
