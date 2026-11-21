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

	/**
	 * The common configuration is stored as one more row of {@code pg_whatsapp_config}, under this reserved property
	 * id (real property ids start at 1). It is set by the super admin and used by every property that has no
	 * configuration of its own switched on.
	 */
	public static final Long COMMON_CONFIG_ID = 0L;

	private final PgWhatsappConfigRepository whatsappRepository;
	private final CryptoService cryptoService;

	/** The configuration to send with for a property: its own when that is switched on, otherwise the common one. */
	public Optional<PgWhatsappConfig> findWhatsapp(Long pgId) {
		Optional<PgWhatsappConfig> own = whatsappRepository.findByPgId(pgId);
		if (own.isPresent() && own.get().isEnabled()) {
			return own;
		}
		return whatsappRepository.findByPgId(COMMON_CONFIG_ID);
	}

	public String whatsappToken(PgWhatsappConfig cfg) {
		return cryptoService.decrypt(cfg.getAccessTokenEncrypted());
	}

	public WhatsappConfigResponse getWhatsappConfig() {
		return getConfig(TenantContext.requirePgId());
	}

	public WhatsappConfigResponse getCommonWhatsappConfig() {
		return getConfig(COMMON_CONFIG_ID);
	}

	private WhatsappConfigResponse getConfig(Long pgId) {
		return whatsappRepository.findByPgId(pgId)
				.map(this::toResponse)
				.orElseGet(() -> new WhatsappConfigResponse(null, pgId, null, null, "v22.0", false, false, true, true, true, true));
	}

	@Transactional
	public WhatsappConfigResponse updateWhatsappConfig(WhatsappConfigRequest req) {
		return updateConfig(TenantContext.requirePgId(), req);
	}

	@Transactional
	public WhatsappConfigResponse updateCommonWhatsappConfig(WhatsappConfigRequest req) {
		return updateConfig(COMMON_CONFIG_ID, req);
	}

	private WhatsappConfigResponse updateConfig(Long pgId, WhatsappConfigRequest req) {
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
