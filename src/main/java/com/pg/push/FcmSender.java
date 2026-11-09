package com.pg.push;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.AndroidNotification;
import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.Notification;
import com.google.firebase.messaging.SendResponse;

import jakarta.annotation.PostConstruct;

/**
 * Talks to Firebase Cloud Messaging. Switched off (and harmless) until a service-account key is configured, either as a
 * file path (app.push.credentials-file) or as the JSON itself (app.push.credentials-json).
 */
@Component
public class FcmSender {

	private static final Logger log = LoggerFactory.getLogger(FcmSender.class);
	/** FCM accepts at most 500 messages per batch. */
	private static final int BATCH = 500;
	/** The notification channel the app creates on the phone (see src/push.ts). */
	static final String CHANNEL_ID = "default";

	private final String credentialsFile;
	private final String credentialsJson;
	private FirebaseMessaging messaging;

	public FcmSender(@Value("${app.push.credentials-file:}") String credentialsFile,
			@Value("${app.push.credentials-json:}") String credentialsJson) {
		this.credentialsFile = credentialsFile == null ? "" : credentialsFile.trim();
		this.credentialsJson = credentialsJson == null ? "" : credentialsJson.trim();
	}

	@PostConstruct
	void init() {
		if (credentialsFile.isEmpty() && credentialsJson.isEmpty()) {
			log.info("Push notifications are off: set FIREBASE_CREDENTIALS_FILE or FIREBASE_CREDENTIALS_JSON to turn them on.");
			return;
		}
		try (InputStream in = credentialsJson.isEmpty() ? new FileInputStream(credentialsFile)
				: new ByteArrayInputStream(credentialsJson.getBytes(StandardCharsets.UTF_8))) {
			FirebaseApp app = FirebaseApp.getApps().isEmpty()
					? FirebaseApp.initializeApp(FirebaseOptions.builder().setCredentials(GoogleCredentials.fromStream(in)).build())
					: FirebaseApp.getInstance();
			messaging = FirebaseMessaging.getInstance(app);
			log.info("Push notifications are on (Firebase project {}).", app.getOptions().getProjectId());
		} catch (Exception ex) {
			log.error("Push notifications stay off: the Firebase credentials could not be read ({}).", ex.getMessage());
		}
	}

	public boolean isEnabled() {
		return messaging != null;
	}

	/** Sends one notification to many devices; returns the tokens FCM says are no longer valid so they can be dropped. */
	public List<String> send(List<String> tokens, String title, String body, Map<String, String> data) {
		List<String> stale = new ArrayList<>();
		if (!isEnabled() || tokens.isEmpty()) {
			return stale;
		}
		for (int from = 0; from < tokens.size(); from += BATCH) {
			List<String> chunk = tokens.subList(from, Math.min(from + BATCH, tokens.size()));
			List<Message> messages = chunk.stream().map(t -> message(t, title, body, data)).toList();
			try {
				BatchResponse result = messaging.sendEach(messages);
				List<SendResponse> responses = result.getResponses();
				for (int i = 0; i < responses.size(); i++) {
					FirebaseMessagingException ex = responses.get(i).getException();
					if (ex == null) {
						continue;
					}
					if (ex.getMessagingErrorCode() == MessagingErrorCode.UNREGISTERED) {
						stale.add(chunk.get(i));
					} else {
						log.warn("Push to one device failed: {}", ex.getMessage());
					}
				}
			} catch (Exception ex) {
				log.warn("Push batch failed: {}", ex.getMessage());
			}
		}
		return stale;
	}

	private static Message message(String token, String title, String body, Map<String, String> data) {
		return Message.builder().setToken(token)
				.setNotification(Notification.builder().setTitle(title).setBody(body).build())
				.putAllData(data)
				.setAndroidConfig(AndroidConfig.builder().setPriority(AndroidConfig.Priority.HIGH)
						.setNotification(AndroidNotification.builder().setChannelId(CHANNEL_ID).build()).build())
				.build();
	}
}
