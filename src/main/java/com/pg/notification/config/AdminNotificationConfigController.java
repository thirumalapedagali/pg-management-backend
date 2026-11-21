package com.pg.notification.config;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pg.notification.WhatsAppService;
import com.pg.notification.config.PgNotificationConfigService.WhatsappConfigRequest;
import com.pg.notification.config.PgNotificationConfigService.WhatsappConfigResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * The common WhatsApp configuration, used by every property that has none of its own switched on. Super admin only
 * (everything under {@code /api/admin} is, see SecurityConfig).
 */
@RestController
@RequestMapping("/api/admin/notification-config/whatsapp")
@RequiredArgsConstructor
public class AdminNotificationConfigController {

	private final PgNotificationConfigService configService;
	private final WhatsAppService whatsAppService;

	@GetMapping
	public WhatsappConfigResponse get() {
		return configService.getCommonWhatsappConfig();
	}

	@PutMapping
	public WhatsappConfigResponse update(@Valid @RequestBody WhatsappConfigRequest req) {
		return configService.updateCommonWhatsappConfig(req);
	}

	@PostMapping("/test")
	public void test(@RequestParam String testPhone) {
		whatsAppService.send(PgNotificationConfigService.COMMON_CONFIG_ID, testPhone,
				"👋 Hello! This is a test message from your PG Management WhatsApp notification system. Config is working properly! ✅");
	}
}
