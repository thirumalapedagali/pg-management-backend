package com.pg.resident;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pg.billing.Billing;
import com.pg.common.AppClock;
import com.pg.common.BadRequestException;
import com.pg.common.NotFoundException;
import com.pg.payment.PaymentRepository;
import com.pg.room.Room;
import com.pg.room.RoomRepository;
import com.pg.tenant.TenantContext;
import com.pg.user.UserRepository;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Service
public class ResidentService {

	public record ResidentRequest(@NotBlank @Size(max = 120) String name,
			@Pattern(regexp = "^$|\\d{10,15}", message = "must be 10-15 digits") String phone, @NotNull Long roomId,
			LocalDate joinedOn) {
	}

	public record ResidentResponse(Long id, String name, String phone, Long roomId, String roomNumber,
			LocalDate joinedOn, BigDecimal rent, BigDecimal billed, BigDecimal paid, BigDecimal due) {
	}

	private final ResidentRepository residents;
	private final RoomRepository rooms;
	private final PaymentRepository payments;
	private final UserRepository users;
	private final Billing billing;
	private final AppClock clock;

	public ResidentService(ResidentRepository residents, RoomRepository rooms, PaymentRepository payments,
			UserRepository users, Billing billing, AppClock clock) {
		this.residents = residents;
		this.rooms = rooms;
		this.payments = payments;
		this.users = users;
		this.billing = billing;
		this.clock = clock;
	}

	public List<ResidentResponse> list() {
		Long pgId = TenantContext.requirePgId();
		Map<Long, Room> roomById = rooms.findByPgIdOrderByNumber(pgId).stream()
				.collect(Collectors.toMap(Room::getId, Function.identity()));
		Map<Long, BigDecimal> paid = new java.util.HashMap<>();
		for (Object[] row : payments.sumByResident(pgId)) {
			paid.put((Long) row[0], (BigDecimal) row[1]);
		}
		return residents.findByPgIdOrderByName(pgId).stream()
				.map(r -> toResponse(r, roomById.get(r.getRoomId()), paid.getOrDefault(r.getId(), BigDecimal.ZERO)))
				.toList();
	}

	public ResidentResponse get(Long id) {
		Resident r = find(id);
		Room room = rooms.findByPgIdAndId(r.getPgId(), r.getRoomId()).orElseThrow();
		return toResponse(r, room, payments.sumForResident(r.getPgId(), r.getId()));
	}

	@Transactional
	public ResidentResponse create(ResidentRequest req) {
		Long pgId = TenantContext.requirePgId();
		Room room = lockRoom(pgId, req.roomId());
		if (residents.countByPgIdAndRoomId(pgId, room.getId()) >= room.getCapacity()) {
			throw new BadRequestException("Room " + room.getNumber() + " is full");
		}
		Resident r = new Resident();
		apply(r, req);
		r = residents.save(r);
		return toResponse(r, room, BigDecimal.ZERO);
	}

	@Transactional
	public ResidentResponse update(Long id, ResidentRequest req) {
		Long pgId = TenantContext.requirePgId();
		Resident r = find(id);
		Room room = lockRoom(pgId, req.roomId());
		if (!room.getId().equals(r.getRoomId())
				&& residents.countByPgIdAndRoomIdAndIdNot(pgId, room.getId(), id) >= room.getCapacity()) {
			throw new BadRequestException("Room " + room.getNumber() + " is full");
		}
		apply(r, req);
		return toResponse(r, room, payments.sumForResident(pgId, id));
	}

	/** Removes the resident, their payment history and their login. */
	@Transactional
	public void delete(Long id) {
		Long pgId = TenantContext.requirePgId();
		Resident r = find(id);
		payments.deleteByPgIdAndResidentId(pgId, id);
		users.findByPgIdAndResidentId(pgId, id).ifPresent(users::delete);
		residents.delete(r);
	}

	private Resident find(Long id) {
		return residents.findByPgIdAndId(TenantContext.requirePgId(), id)
				.orElseThrow(() -> new NotFoundException("Resident not found"));
	}

	private Room lockRoom(Long pgId, Long roomId) {
		return rooms.lockByPgIdAndId(pgId, roomId).orElseThrow(() -> new BadRequestException("Room not found"));
	}

	private void apply(Resident r, ResidentRequest req) {
		r.setName(req.name().trim());
		r.setPhone(req.phone() == null || req.phone().isBlank() ? null : req.phone());
		r.setRoomId(req.roomId());
		if (req.joinedOn() != null) {
			r.setJoinedOn(req.joinedOn());
		} else if (r.getJoinedOn() == null) {
			r.setJoinedOn(clock.today());
		}
	}

	private ResidentResponse toResponse(Resident r, Room room, BigDecimal paid) {
		BigDecimal rent = room == null ? BigDecimal.ZERO : room.getRent();
		BigDecimal billed = room == null ? BigDecimal.ZERO : billing.billed(r, room);
		return new ResidentResponse(r.getId(), r.getName(), r.getPhone(), r.getRoomId(),
				room == null ? null : room.getNumber(), r.getJoinedOn(), rent, billed, paid, Billing.due(billed, paid));
	}
}
