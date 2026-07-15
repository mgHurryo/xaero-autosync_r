package cn.net.rms.xaeromapsync_r.xaero;

public final class XaeroWaypointReconcileResult {
	public enum Outcome {
		APPLIED,
		NO_CHANGES,
		UNAVAILABLE,
		FAILED
	}

	private final Outcome outcome;
	private final int created;
	private final int updated;
	private final int deleted;
	private final int ignored;
	private final boolean saved;
	private final String message;

	private XaeroWaypointReconcileResult(Outcome outcome, int created, int updated, int deleted, int ignored, boolean saved, String message) {
		this.outcome = outcome;
		this.created = created;
		this.updated = updated;
		this.deleted = deleted;
		this.ignored = ignored;
		this.saved = saved;
		this.message = message;
	}

	static XaeroWaypointReconcileResult completed(int created, int updated, int deleted, int ignored, boolean saved) {
		Outcome outcome = saved ? Outcome.APPLIED : Outcome.NO_CHANGES;
		return new XaeroWaypointReconcileResult(outcome, created, updated, deleted, ignored, saved, saved ? "Xaero waypoints reconciled" : "Xaero waypoints already reconciled");
	}

	static XaeroWaypointReconcileResult unavailable(String message) {
		return new XaeroWaypointReconcileResult(Outcome.UNAVAILABLE, 0, 0, 0, 0, false, message);
	}

	static XaeroWaypointReconcileResult failed(int ignored, String message) {
		return new XaeroWaypointReconcileResult(Outcome.FAILED, 0, 0, 0, ignored, false, message);
	}

	public Outcome outcome() {
		return outcome;
	}

	public int created() {
		return created;
	}

	public int updated() {
		return updated;
	}

	public int deleted() {
		return deleted;
	}

	public int ignored() {
		return ignored;
	}

	public boolean saved() {
		return saved;
	}

	public String message() {
		return message;
	}
}
