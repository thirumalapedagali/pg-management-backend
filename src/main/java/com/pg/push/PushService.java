package com.pg.push;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import com.pg.room.Room;
import com.pg.room.RoomRepository;
import com.pg.room.RoomSection;
import com.pg.room.RoomSectionRepository;
import com.pg.room.Bed;
import com.pg.room.BedRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import jakarta.annotation.PreDestroy;

/**
 * Sends push notifications to residents. Callers just say who and what; this waits for the surrounding transaction to
 * commit (so nobody is told about something that was rolled back), then delivers off the request thread. It never throws:
 * a failed push must not fail the action that caused it.
 */
@Service
public class PushService {

	private static final Logger log = LoggerFactory.getLogger(PushService.class);
	private static final int MAX_BODY = 160;

	private final FcmSender sender;
	private final DeviceTokenRepository tokens;
	private final com.pg.resident.ResidentRepository residents;
	private final com.pg.user.UserRepository users;
	private final com.pg.notification.WhatsAppService whatsApp;
	private final RoomRepository rooms;
	private final RoomSectionRepository sections;
	private final BedRepository beds;
	private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
		Thread t = new Thread(r, "push-sender");
		t.setDaemon(true);
		return t;
	});

	public PushService(FcmSender sender, DeviceTokenRepository tokens,
			com.pg.resident.ResidentRepository residents, com.pg.user.UserRepository users,
			com.pg.notification.WhatsAppService whatsApp, RoomRepository rooms,
			RoomSectionRepository sections, BedRepository beds) {
		this.sender = sender;
		this.tokens = tokens;
		this.residents = residents;
		this.users = users;
		this.whatsApp = whatsApp;
		this.rooms = rooms;
		this.sections = sections;
		this.beds = beds;
	}

	public String formatResidentDetails(Long pgId, Long residentId) {
		if (residentId == null) {
			return "";
		}
		com.pg.resident.Resident r = residents.findByPgIdAndId(pgId, residentId).orElse(null);
		return formatResidentDetails(pgId, r);
	}

	public String formatResidentDetails(Long pgId, com.pg.resident.Resident r) {
		if (r == null) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		sb.append(r.getName());
		if (r.getRoomId() != null) {
			Room room = rooms.findByPgIdAndId(pgId, r.getRoomId()).orElse(null);
			if (room != null) {
				sb.append(" (Room ").append(room.getNumber());
				if (r.getSectionId() != null) {
					RoomSection sec = sections.findByPgIdAndId(pgId, r.getSectionId()).orElse(null);
					if (sec != null && !"Main Room".equals(sec.getSectionName())) {
						sb.append(" · ").append(sec.getSectionName());
					}
				}
				if (r.getBedId() != null) {
					Bed bed = beds.findByPgIdAndId(pgId, r.getBedId()).orElse(null);
					if (bed != null) {
						sb.append(" · Bed ").append(bed.getBedNumber());
					}
				}
				sb.append(")");
			}
		}
		return sb.toString();
	}

	public boolean hasTokensForResident(Long pgId, Long residentId) {
		if (residentId == null) {
			return false;
		}
		return !tokens.tokensForResident(pgId, residentId).isEmpty();
	}

	/** Everyone living in the property who has a login. */
	public void toPg(Long pgId, String title, String body, Map<String, String> data) {
		queue(() -> tokens.tokensForPg(pgId), title, body, data, null);
	}

	public void toPg(Long pgId, String title, String body) {
		toPg(pgId, title, body, Map.of());
	}

	/** One resident. */
	public void toResident(Long pgId, Long residentId, String title, String body, Map<String, String> data) {
		if (residentId == null) {
			return;
		}
		queue(() -> tokens.tokensForResident(pgId, residentId), title, body, data, () -> {
			if (pgId != null) {
				residents.findById(residentId).ifPresent(res -> {
					if (res.getPhone() != null && !res.getPhone().isBlank()) {
						String whatsappMsg = "🔔 *" + title + "*\n\n" + body + "\n\n📲 _Download our app to get real-time push notifications!_";
						whatsApp.send(pgId, res.getPhone(), whatsappMsg);
					}
				});
			}
		});
	}

	public void toResident(Long pgId, Long residentId, String title, String body) {
		toResident(pgId, residentId, title, body, Map.of());
	}

	/** Everyone working in the property (owner and managers). */
	public void toStaff(Long pgId, String title, String body, Map<String, String> data) {
		if (pgId == null) {
			return;
		}
		queue(() -> tokens.tokensForStaff(pgId), title, body, data, () -> {
			users.findByPgIdAndRoleInAndStatus(pgId, List.of(com.pg.user.Role.OWNER, com.pg.user.Role.MANAGER), com.pg.user.Status.ACTIVE).forEach(u -> {
				if (u.getMobile() != null && !u.getMobile().isBlank()) {
					String whatsappMsg = "🔔 *" + title + "*\n\n" + body + "\n\n📲 _Download our app to get real-time push notifications!_";
					whatsApp.send(pgId, u.getMobile(), whatsappMsg);
				}
			});
		});
	}

	public void toStaff(Long pgId, String title, String body) {
		toStaff(pgId, title, body, Map.of());
	}

	/** Any user with the given registered mobile number. */
	public void toUserByMobile(Long pgId, String mobile, String title, String body, Map<String, String> data) {
		if (mobile == null || mobile.isBlank()) {
			return;
		}
		queue(() -> tokens.tokensForUserByMobile(mobile.trim()), title, body, data, () -> {
			if (pgId != null) {
				String whatsappMsg = "🔔 *" + title + "*\n\n" + body + "\n\n📲 _Download our app to get real-time push notifications!_";
				whatsApp.send(pgId, mobile.trim(), whatsappMsg);
			}
		});
	}

	public void toUserByMobile(Long pgId, String mobile, String title, String body) {
		toUserByMobile(pgId, mobile, title, body, Map.of());
	}

	/** Everyone with SUPER_ADMIN role. */
	public void toSuperAdmins(String title, String body, Map<String, String> data) {
		queue(() -> tokens.tokensForSuperAdmins(), title, body, data, null);
	}

	public void toSuperAdmins(String title, String body) {
		toSuperAdmins(title, body, Map.of());
	}

	private void queue(Supplier<List<String>> recipients, String title, String body, Map<String, String> data, Runnable fallback) {
		if (!sender.isEnabled()) {
			if (fallback != null) {
				fallback.run();
			}
			return;
		}
		Runnable work = () -> executor.execute(() -> {
			try {
				List<String> to = recipients.get();
				if (to.isEmpty()) {
					if (fallback != null) {
						fallback.run();
					}
					return;
				}
				List<String> stale = sender.send(to, title, shorten(body), data);
				if (!stale.isEmpty()) {
					tokens.deleteByTokens(stale);
				}
			} catch (Exception ex) {
				log.warn("Could not send the push notification: {}", ex.getMessage());
				if (fallback != null) {
					fallback.run();
				}
			}
		});
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					work.run();
				}
			});
		} else {
			work.run();
		}
	}

	private static String shorten(String text) {
		if (text == null) {
			return "";
		}
		String t = text.strip();
		return t.length() <= MAX_BODY ? t : t.substring(0, MAX_BODY - 1) + "...";
	}

	@PreDestroy
	void stop() {
		executor.shutdown();
	}
}
