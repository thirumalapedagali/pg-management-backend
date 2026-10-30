package com.pg.tenant;

import com.pg.common.BadRequestException;

/** Per-request tenant (PG) and role, populated by the JWT filter. */
public final class TenantContext {

	private static final ThreadLocal<Long> PG_ID = new ThreadLocal<>();
	private static final ThreadLocal<String> ROLE = new ThreadLocal<>();

	private TenantContext() {
	}

	public static void set(Long pgId, String role) {
		PG_ID.set(pgId);
		ROLE.set(role);
	}

	public static Long getPgId() {
		return PG_ID.get();
	}

	public static String getRole() {
		return ROLE.get();
	}

	public static boolean isSuperAdmin() {
		return "SUPER_ADMIN".equals(ROLE.get());
	}

	/** The PG every tenant-scoped query must use. A super admin who has not picked a PG has none. */
	public static Long requirePgId() {
		Long id = PG_ID.get();
		if (id == null) {
			throw new BadRequestException("No PG selected for this session");
		}
		return id;
	}

	public static void clear() {
		PG_ID.remove();
		ROLE.remove();
	}
}
