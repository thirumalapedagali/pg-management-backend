package com.pg.push;

import java.time.LocalDateTime;

import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pg.common.UnauthorizedException;
import com.pg.user.UserEntity;
import com.pg.user.UserRepository;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A resident's phone tells us where to send push notifications (and stops them on sign-out). Residents only: /api/me. */
@RestController
@RequestMapping("/api/me/devices")
public class DeviceController {

	public record RegisterDevice(@NotBlank @Size(max = 400) String token, @Size(max = 20) String platform) {
	}

	private final DeviceTokenRepository devices;
	private final UserRepository users;

	public DeviceController(DeviceTokenRepository devices, UserRepository users) {
		this.devices = devices;
		this.users = users;
	}

	@PostMapping
	@Transactional
	public void register(@Valid @RequestBody RegisterDevice req, Authentication auth) {
		UserEntity user = currentUser(auth);
		DeviceToken d = devices.findByToken(req.token().trim()).orElseGet(DeviceToken::new);
		d.setToken(req.token().trim());
		d.setUserId(user.getId());
		d.setPlatform(req.platform());
		d.setUpdatedAt(LocalDateTime.now());
		devices.save(d);
	}

	@DeleteMapping
	@Transactional
	public void unregister(@RequestParam String token, Authentication auth) {
		devices.deleteByTokenAndUser(token.trim(), currentUser(auth).getId());
	}

	private UserEntity currentUser(Authentication auth) {
		return users.findByUsername(auth.getName()).orElseThrow(() -> new UnauthorizedException("Invalid token"));
	}
}
