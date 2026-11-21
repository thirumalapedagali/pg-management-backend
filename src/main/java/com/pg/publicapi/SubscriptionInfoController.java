package com.pg.publicapi;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public: where an owner pays to renew. It must work without a valid token, because an owner whose subscription
 * has expired cannot sign in. The super admin extends the licence once the payment is confirmed.
 */
@RestController
public class SubscriptionInfoController {

	public record PaymentInfo(String upiId, String upiName, String supportMobile) {
	}

	private final PaymentInfo info;

	public SubscriptionInfoController(@Value("${app.subscription.upi-id:}") String upiId,
			@Value("${app.subscription.upi-name:}") String upiName,
			@Value("${app.subscription.support-mobile:}") String supportMobile) {
		this.info = new PaymentInfo(blankToNull(upiId), blankToNull(upiName), blankToNull(supportMobile));
	}

	@GetMapping("/api/public/subscription")
	public PaymentInfo paymentInfo() {
		return info;
	}

	private static String blankToNull(String s) {
		return s == null || s.isBlank() ? null : s.trim();
	}
}
