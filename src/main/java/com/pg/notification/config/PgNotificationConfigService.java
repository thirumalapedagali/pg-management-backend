package com.pg.notification.config;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pg.common.BadRequestException;
import com.pg.tenant.TenantContext;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PgNotificationConfigService {

	public record WhatsappConfigRequest(
			String phoneNumberId,
			String wabaId,
			String accessToken,
			String apiVersion,
			Boolean enabled,
			Boolean notifyOnPayment,
			Boolean notifyOnDueReminder,
			Boolean notifyOnOnboarding,
			Boolean notifyOnMove) {
	}

	public record WhatsappConfigResponse(
			Long id,
			Long pgId,
			String phoneNumberId,
			String wabaId,
			String apiVersion,
			boolean hasAccessToken,
			boolean enabled,
			boolean notifyOnPayment,
			boolean notifyOnDueReminder,
			boolean notifyOnOnboarding,
			boolean notifyOnMove) {
	}

	private final PgWhatsappConfigRepository whatsappRepository;
	private final CryptoService cryptoService;

	public Optional<PgWhatsappConfig> findWhatsapp(Long pgId) {
		return whatsappRepository.findByPgId(pgId);
	}

	public String whatsappToken(PgWhatsappConfig cfg) {
		return cryptoService.decrypt(cfg.getAccessTokenEncrypted());
	}

	public WhatsappConfigResponse getWhatsappConfig() {
		Long pgId = TenantContext.requirePgId();
		return whatsappRepository.findByPgId(pgId)
				.map(this::toResponse)
				.orElseGet(() -> new WhatsappConfigResponse(null, pgId, null, null, "v22.0", false, false, true, true, true, true));
	}

	@Transactional
	public WhatsappConfigResponse updateWhatsappConfig(WhatsappConfigRequest req) {
		Long pgId = TenantContext.requirePgId();
		PgWhatsappConfig cfg = whatsappRepository.findByPgId(pgId).orElseGet(() -> {
			PgWhatsappConfig created = new PgWhatsappConfig();
			created.setPgId(pgId);
			return created;
		});

		if (req.phoneNumberId() != null) {
			cfg.setPhoneNumberId(req.phoneNumberId().trim());
		}
		if (req.wabaId() != null) {
			cfg.setWabaId(req.wabaId().trim());
		}
		if (req.apiVersion() != null && !req.apiVersion().isBlank()) {
			cfg.setApiVersion(req.apiVersion().trim());
		}
		if (req.accessToken() != null && !req.accessToken().isBlank()) {
			cfg.setAccessTokenEncrypted(cryptoService.encrypt(req.accessToken().trim()));
		}
		if (req.enabled() != null) {
			if (req.enabled() && (cfg.getAccessTokenEncrypted() == null || cfg.getPhoneNumberId() == null || cfg.getPhoneNumberId().isBlank())) {
				throw new BadRequestException("Cannot enable WhatsApp without Phone Number ID and Access Token");
			}
			cfg.setEnabled(req.enabled());
		}
		if (req.notifyOnPayment() != null) {
			cfg.setNotifyOnPayment(req.notifyOnPayment());
		}
		if (req.notifyOnDueReminder() != null) {
			cfg.setNotifyOnDueReminder(req.notifyOnDueReminder());
		}
		if (req.notifyOnOnboarding() != null) {
			cfg.setNotifyOnOnboarding(req.notifyOnOnboarding());
		}
		if (req.notifyOnMove() != null) {
			cfg.setNotifyOnMove(req.notifyOnMove());
		}

		return toResponse(whatsappRepository.save(cfg));
	}

	private WhatsappConfigResponse toResponse(PgWhatsappConfig cfg) {
		return new WhatsappConfigResponse(
				cfg.getId(),
				cfg.getPgId(),
				cfg.getPhoneNumberId(),
				cfg.getWabaId(),
				cfg.getApiVersion(),
				cfg.getAccessTokenEncrypted() != null && !cfg.getAccessTokenEncrypted().isBlank(),
				cfg.isEnabled(),
				cfg.isNotifyOnPayment(),
				cfg.isNotifyOnDueReminder(),
				cfg.isNotifyOnOnboarding(),
				cfg.isNotifyOnMove()
		);
	}
}
