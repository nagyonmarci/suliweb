package hu.fmdev.backend.domain;

public record StageProgress(
        String id,
        String name,
        StageState state,
        long total,
        long processed,
        int percentage,
        String detail,
        Double ratePerMin,
        Long etaSeconds
) {
    public static StageProgress pending(String id, String name) {
        return new StageProgress(id, name, StageState.PENDING, 0, 0, 0, null, null, null);
    }

    public static StageProgress skipped(String id, String name) {
        return new StageProgress(id, name, StageState.SKIPPED, 0, 0, 100, null, null, null);
    }

    public StageProgress withState(StageState newState) {
        return new StageProgress(id, name, newState, total, processed, percentage, detail, ratePerMin, etaSeconds);
    }
}
