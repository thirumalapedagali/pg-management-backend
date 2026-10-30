package com.pg.tenant;

import jakarta.persistence.PrePersist;

/** Safety net: a tenant row can never be saved without a PG id. */
public class TenantEntityListener {

	@PrePersist
	public void setPgId(BaseEntity entity) {
		if (entity.getPgId() == null) {
			entity.setPgId(TenantContext.requirePgId());
		}
	}
}
