package dev.guizhou.util.combat;

/** Ranks already-copied scores. The caller must not pass live world or player objects. */
public final class OffthreadRank {
    public record Job(long key, float[] scores, long[] cells) {}

    private final CombatPlanningTask<Job, Long> task = new CombatPlanningTask<>(OffthreadRank::bestCell);
    private long hint = Long.MIN_VALUE;

    public void submit(long key, float[] scores, long[] cells) {
        if (scores.length == 0 || scores.length != cells.length) return;
        task.submit(new Job(key, scores, cells));
    }

    /** Latest finished cell for this key, or the previous hint when this tick's job is still running. */
    public long poll(long key) {
        CombatPlanningTask.Completed<Job, Long> done = task.poll();
        if (done != null && !done.failed() && done.output() != null && done.input().key() == key) hint = done.output();
        return hint;
    }

    public static long bestCell(Job job) {
        int best = -1;
        float score = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < job.scores().length && i < job.cells().length; i++) {
            if (job.scores()[i] > score) {
                score = job.scores()[i];
                best = i;
            }
        }
        return best < 0 ? Long.MIN_VALUE : job.cells()[best];
    }
}
