package com.pg.notification;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.pg.notification.config.PgNotificationConfigService;
import com.pg.notification.config.PgWhatsappConfig;
import com.pg.notification.usage.WhatsappMessageLog;
import com.pg.notification.usage.WhatsappMessageLogRepository;

import lombok.RequiredArgsConstructor;

/**
 * Sends WhatsApp notifications via Meta WhatsApp Cloud API.
 * Uses credentials configured per-PG (pg_whatsapp_config).
 */
@Service
@RequiredArgsConstructor
public class WhatsAppService {

	private static final Logger logger = LoggerFactory.getLogger(WhatsAppService.class);
	private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd-MMM-yyyy");

	private final PgNotificationConfigService configService;
	private final WhatsappMessageLogRepository logRepository;
	private final RestTemplate restTemplate = new RestTemplate();

	public void send(Long pgId, String to, String message) {
		post(pgId, to, "text", textBody(message), "GENERAL");
	}

	public void sendPaymentReceipt(Long pgId, String pgName, String residentName, String phone, String roomNumber,
			BigDecimal amount, BigDecimal remainingDue, LocalDate paymentDate) {
		if (phone == null || phone.isBlank()) {
			return;
		}

		Optional<PgWhatsappConfig> cfg = configService.findWhatsapp(pgId);
		if (cfg.isEmpty() || !cfg.get().isEnabled() || !cfg.get().isNotifyOnPayment()) {
			return;
		}

		String dateStr = paymentDate != null ? paymentDate.format(DATE_FORMATTER) : LocalDate.now().format(DATE_FORMATTER);
		String dueStr = remainingDue != null && remainingDue.signum() > 0 ? "₹" + remainingDue.toPlainString() : "₹0 (Fully Paid)";

		String msg = String.format(
				"🧾 *Payment Received - %s*\n\n"
				+ "Hi %s,\n"
				+ "We have successfully received your payment.\n\n"
				+ "💵 *Amount Paid:* ₹%s\n"
				+ "📅 *Date:* %s\n"
				+ "🚪 *Room:* %s\n"
				+ "📊 *Pending Balance:* %s\n\n"
				+ "Thank you for staying with us! 🙏",
				pgName != null ? pgName : "PG Management",
				residentName,
				amount.toPlainString(),
				dateStr,
				roomNumber != null ? roomNumber : "-",
				dueStr
		);

		post(pgId, phone, "text", textBody(msg), "PAYMENT_RECEIPT");
	}

	public void sendDueReminder(Long pgId, String pgName, String residentName, String phone, String roomNumber,
			BigDecimal dueAmount, String pgContactPhone) {
		if (phone == null || phone.isBlank() || dueAmount == null || dueAmount.signum() <= 0) {
			return;
		}

		Optional<PgWhatsappConfig> cfg = configService.findWhatsapp(pgId);
		if (cfg.isEmpty() || !cfg.get().isEnabled() || !cfg.get().isNotifyOnDueReminder()) {
			return;
		}

		String contactInfo = (pgContactPhone != null && !pgContactPhone.isBlank())
				? "\n📞 For queries or UPI details, contact: " + pgContactPhone
				: "";

		String msg = String.format(
				"📢 *Payment Due Reminder - %s*\n\n"
				+ "Dear %s,\n"
				+ "This is a gentle reminder that your pending PG fee for Room %s is *₹%s*.\n\n"
				+ "Kindly clear the outstanding dues at your earliest convenience to ensure uninterrupted services.%s\n\n"
				+ "Thank you! 🙏",
				pgName != null ? pgName : "PG Management",
				residentName,
				roomNumber != null ? roomNumber : "-",
				dueAmount.toPlainString(),
				contactInfo
		);

		post(pgId, phone, "text", textBody(msg), "DUE_REMINDER");
	}

	public void sendWelcomeMessage(Long pgId, String pgName, String residentName, String phone, String roomNumber,
			String bedNumber, BigDecimal rent, BigDecimal deposit, LocalDate joinedOn) {
		if (phone == null || phone.isBlank()) {
			return;
		}

		Optional<PgWhatsappConfig> cfg = configService.findWhatsapp(pgId);
		if (cfg.isEmpty() || !cfg.get().isEnabled() || !cfg.get().isNotifyOnOnboarding()) {
			return;
		}

		String joinedStr = joinedOn != null ? joinedOn.format(DATE_FORMATTER) : LocalDate.now().format(DATE_FORMATTER);
		String bedInfo = bedNumber != null ? " (Bed: " + bedNumber + ")" : "";

		String msg = String.format(
				"🎉 *Welcome to %s!*\n\n"
				+ "Hi %s,\n"
				+ "We are pleased to welcome you to our PG community.\n\n"
				+ "🏡 *Allocated Space:* Room %s%s\n"
				+ "📅 *Join Date:* %s\n"
				+ "💵 *Monthly Rent:* ₹%s\n"
				+ "🔒 *Security Deposit:* ₹%s\n\n"
				+ "Feel free to reach out to management for any assistance.\n"
				+ "Wish you a comfortable stay! 🏠✨",
				pgName != null ? pgName : "PG",
				residentName,
				roomNumber != null ? roomNumber : "-",
				bedInfo,
				joinedStr,
				rent != null ? rent.toPlainString() : "0",
				deposit != null ? deposit.toPlainString() : "0"
		);

		post(pgId, phone, "text", textBody(msg), "WELCOME");
	}

	public void sendMoveNotification(Long pgId, String pgName, String residentName, String phone,
			String oldRoomNumber, String newRoomNumber, String bedNumber, BigDecimal newRent) {
		if (phone == null || phone.isBlank()) {
			return;
		}

		Optional<PgWhatsappConfig> cfg = configService.findWhatsapp(pgId);
		if (cfg.isEmpty() || !cfg.get().isEnabled() || !cfg.get().isNotifyOnMove()) {
			return;
		}

		String bedInfo = bedNumber != null ? " (Bed: " + bedNumber + ")" : "";

		String msg = String.format(
				"🔄 *Room Relocation Update - %s*\n\n"
				+ "Hi %s,\n"
				+ "Your room allocation has been updated successfully.\n\n"
				+ "🚪 *Previous Room:* %s\n"
				+ "🏡 *New Room:* %s%s\n"
				+ "💵 *Updated Rent:* ₹%s\n\n"
				+ "Please reach out to the warden or manager if you need help settling in! 🙏",
				pgName != null ? pgName : "PG Management",
				residentName,
				oldRoomNumber != null ? oldRoomNumber : "-",
				newRoomNumber != null ? newRoomNumber : "-",
				bedInfo,
				newRent != null ? newRent.toPlainString() : "0"
		);

		post(pgId, phone, "text", textBody(msg), "ROOM_MOVE");
	}

	private Map<String, String> textBody(String message) {
		Map<String, String> text = new HashMap<>();
		text.put("body", message);
		return text;
	}

	private void post(Long pgId, String to, String type, Map<String, String> content, String messageType) {
		Optional<PgWhatsappConfig> found = configService.findWhatsapp(pgId);
		if (found.isEmpty() || !found.get().isEnabled()) {
			logger.info("WhatsApp not enabled for PG {}. Message skipped.", pgId);
			return;
		}
		PgWhatsappConfig cfg = found.get();

		String formattedPhone = sanitizePhoneNumber(to);
		if (formattedPhone == null) {
			logger.warn("Invalid phone number for WhatsApp notification: {}", to);
			record(pgId, to, messageType, WhatsappMessageLog.FAILED, "Invalid phone number format");
			return;
		}

		try {
			String apiVersion = (cfg.getApiVersion() != null && !cfg.getApiVersion().isBlank()) ? cfg.getApiVersion() : "v22.0";
			String url = "https://graph.facebook.com/" + apiVersion + "/" + cfg.getPhoneNumberId() + "/messages";

			HttpHeaders headers = new HttpHeaders();
			headers.setBearerAuth(configService.whatsappToken(cfg));
			headers.setContentType(MediaType.APPLICATION_JSON);

			Map<String, Object> body = new HashMap<>();
			body.put("messaging_product", "whatsapp");
			body.put("to", formattedPhone);
			body.put("type", type);
			body.put(type, content);

			ResponseEntity<String> response = restTemplate.postForEntity(url, new HttpEntity<>(body, headers), String.class);
			logger.info("WhatsApp API response (pg={}, to={}): {}", pgId, formattedPhone, response.getBody());
			record(pgId, formattedPhone, messageType, WhatsappMessageLog.SENT, null);
		} catch (Exception e) {
			logger.error("Error sending WhatsApp message (pg={}, to={}): {}", pgId, formattedPhone, e.getMessage());
			record(pgId, formattedPhone, messageType, WhatsappMessageLog.FAILED, e.getMessage());
		}
	}

	private String sanitizePhoneNumber(String raw) {
		if (raw == null) {
			return null;
		}
		String digits = raw.replaceAll("[^0-9]", "");
		if (digits.length() == 10) {
			return "91" + digits; // Default India prefix
		} else if (digits.length() == 12 && digits.startsWith("91")) {
			return digits;
		} else if (digits.length() >= 11 && digits.length() <= 15) {
			return digits;
		}
		return null;
	}

	private void record(Long pgId, String to, String messageType, String status, String error) {
		try {
			WhatsappMessageLog log = new WhatsappMessageLog();
			log.setPgId(pgId);
			log.setRecipient(to);
			log.setMessageType(messageType);
			log.setStatus(status);
			if (error != null) {
				log.setError(error.length() > 500 ? error.substring(0, 500) : error);
			}
			logRepository.save(log);
		} catch (Exception e) {
			logger.warn("Could not record WhatsApp message log (pg={}): {}", pgId, e.getMessage());
		}
	}
}
