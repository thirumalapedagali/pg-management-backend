package com.pg.complaint;

public enum ComplaintStatus {
	OPEN("Open"), IN_PROGRESS("In Progress"), RESOLVED("Resolved");

	private final String label;

	ComplaintStatus(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}
}
