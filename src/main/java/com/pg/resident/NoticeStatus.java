package com.pg.resident;

/** Where a resident's notice to vacate stands. A null status on the resident means no notice. */
public enum NoticeStatus {
	/** Resident asked to leave on a date; waiting for the owner. */
	REQUESTED,
	/** Owner accepted: the bed is advertised as available from the vacate date. */
	APPROVED,
	/** Owner declined; the resident stays and may raise a new notice. */
	REJECTED
}
