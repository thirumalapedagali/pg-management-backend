package com.pg.user;

public enum Role {
	/** Platform operator: manages PGs and licences, can act inside any PG after selecting it. */
	SUPER_ADMIN,
	/** PG owner: full access inside their own PG. */
	OWNER,
	/** Staff: day-to-day operations, cannot manage users. */
	MANAGER,
	/** Tenant of the PG: sees own dues and room availability only. */
	RESIDENT
}
