package com.pg.room;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pg.common.BadRequestException;
import com.pg.common.NotFoundException;
import com.pg.resident.Resident;
import com.pg.resident.ResidentRepository;
import com.pg.tenant.TenantContext;
import com.pg.user.Role;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Service
public class RoomService {

	public record RoomRequest(@NotBlank @Size(max = 30) String number, @Min(1) int capacity,
			@NotNull @DecimalMin("0.00") BigDecimal rent) {
	}

	/** occupants is null for residents (they only see availability). */
	public record RoomResponse(Long id, String number, int capacity, BigDecimal rent, int occupied, int vacant,
			List<String> occupants) {
	}

	private final RoomRepository rooms;
	private final ResidentRepository residents;

	public RoomService(RoomRepository rooms, ResidentRepository residents) {
		this.rooms = rooms;
		this.residents = residents;
	}

	public List<RoomResponse> list() {
		Long pgId = TenantContext.requirePgId();
		boolean staff = !Role.RESIDENT.name().equals(TenantContext.getRole());
		Map<Long, List<String>> byRoom = new HashMap<>();
		for (Resident r : residents.findByPgIdOrderByName(pgId)) {
			byRoom.computeIfAbsent(r.getRoomId(), k -> new java.util.ArrayList<>()).add(r.getName());
		}
		return rooms.findByPgIdOrderByNumber(pgId).stream().map(r -> {
			List<String> names = byRoom.getOrDefault(r.getId(), List.of());
			return new RoomResponse(r.getId(), r.getNumber(), r.getCapacity(), r.getRent(), names.size(),
					Math.max(0, r.getCapacity() - names.size()), staff ? names : null);
		}).toList();
	}

	@Transactional
	public RoomResponse create(RoomRequest req) {
		Long pgId = TenantContext.requirePgId();
		String number = req.number().trim();
		if (rooms.existsByPgIdAndNumber(pgId, number)) {
			throw new BadRequestException("Room " + number + " already exists");
		}
		Room room = new Room();
		apply(room, req, number);
		return toResponse(rooms.save(room));
	}

	@Transactional
	public RoomResponse update(Long id, RoomRequest req) {
		Long pgId = TenantContext.requirePgId();
		Room room = rooms.lockByPgIdAndId(pgId, id).orElseThrow(() -> new NotFoundException("Room not found"));
		String number = req.number().trim();
		if (rooms.existsByPgIdAndNumberAndIdNot(pgId, number, id)) {
			throw new BadRequestException("Room " + number + " already exists");
		}
		long occupied = residents.countByPgIdAndRoomId(pgId, id);
		if (req.capacity() < occupied) {
			throw new BadRequestException("Capacity cannot be below the current " + occupied + " residents");
		}
		apply(room, req, number);
		return toResponse(room);
	}

	@Transactional
	public void delete(Long id) {
		Long pgId = TenantContext.requirePgId();
		Room room = rooms.lockByPgIdAndId(pgId, id).orElseThrow(() -> new NotFoundException("Room not found"));
		if (residents.countByPgIdAndRoomId(pgId, id) > 0) {
			throw new BadRequestException("Move or remove the residents in this room first");
		}
		rooms.delete(room);
	}

	private void apply(Room room, RoomRequest req, String number) {
		room.setNumber(number);
		room.setCapacity(req.capacity());
		room.setRent(req.rent());
	}

	private RoomResponse toResponse(Room r) {
		List<String> names = residents.findByPgIdAndRoomIdOrderByName(r.getPgId(), r.getId()).stream()
				.map(Resident::getName).toList();
		return new RoomResponse(r.getId(), r.getNumber(), r.getCapacity(), r.getRent(), names.size(),
				Math.max(0, r.getCapacity() - names.size()), names);
	}
}
