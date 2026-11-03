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
import com.pg.tenant.TenantContext;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/notification-config/whatsapp")
@RequiredArgsConstructor
public class NotificationConfigController {

	private final PgNotificationConfigService configService;
	private final WhatsAppService whatsAppService;

	@GetMapping
	public WhatsappConfigResponse get() {
		return configService.getWhatsappConfig();
	}

	@PutMapping
	public WhatsappConfigResponse update(@Valid @RequestBody WhatsappConfigRequest req) {
		return configService.updateWhatsappConfig(req);
	}

	@PostMapping("/test")
	public void test(@RequestParam String testPhone) {
		Long pgId = TenantContext.requirePgId();
		whatsAppService.send(pgId, testPhone, "👋 Hello! This is a test message from your PG Management WhatsApp notification system. Config is working properly! ✅");
	}
}
