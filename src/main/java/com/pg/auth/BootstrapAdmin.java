package com.pg.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.pg.user.Role;
import com.pg.user.UserEntity;
import com.pg.user.UserRepository;

/** Creates the first SUPER_ADMIN when none exists and a password is supplied via configuration. */
@Component
public class BootstrapAdmin implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(BootstrapAdmin.class);

	private final UserRepository users;
	private final PasswordEncoder encoder;
	private final String username;
	private final String mobile;
	private final String password;

	public BootstrapAdmin(UserRepository users, PasswordEncoder encoder,
			@Value("${app.bootstrap-admin.username}") String username,
			@Value("${app.bootstrap-admin.mobile}") String mobile,
			@Value("${app.bootstrap-admin.password:}") String password) {
		this.users = users;
		this.encoder = encoder;
		this.username = username;
		this.mobile = mobile;
		this.password = password;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (users.existsByUsername(username)) {
			return;
		}
		if (password == null || password.length() < 8) {
			log.warn("No SUPER_ADMIN exists. Set BOOTSTRAP_ADMIN_PASSWORD (min 8 chars) and restart to create one.");
			return;
		}
		UserEntity admin = new UserEntity();
		admin.setUsername(username);
		admin.setMobile(mobile);
		admin.setPassword(encoder.encode(password));
		admin.setRole(Role.SUPER_ADMIN);
		admin.setMustChangePassword(true);
		users.save(admin);
		log.info("Created SUPER_ADMIN '{}'. Change its password after first login.", username);
	}
}
