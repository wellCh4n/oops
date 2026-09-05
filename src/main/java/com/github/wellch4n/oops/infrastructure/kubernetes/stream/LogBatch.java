package com.github.wellch4n.oops.infrastructure.kubernetes.stream;

import java.util.ArrayList;
import java.util.List;

/**
 * One {@code log} event in the making: the {@code {time, text}} lines read since the last flush.
 *
 * <p>Lines are batched rather than sent one per event because a container writes tens of thousands
 * of them, most only a few bytes long — git and buildah redraw progress with a bare carriage return,
 * which the reader ends a line on — so the per-event envelope would otherwise outweigh the log
 * itself. Callers flush as soon as the reader has nothing more buffered, so a live stream still
 * shows each line the moment it arrives; only a replay of finished output fills a batch.
 */
final class LogBatch {
    static final int MAX_LINES = 500;
    static final int MAX_BYTES = 64 * 1024;

    private final List<LogLine> lines = new ArrayList<>();
    private int bytes;
    private String lastTime;

    void add(String time, String text) {
        lines.add(new LogLine(time, text));
        bytes += text.length();
        if (time != null) {
            lastTime = time;
        }
    }

    boolean isFull() {
        return lines.size() >= MAX_LINES || bytes >= MAX_BYTES;
    }

    boolean isEmpty() {
        return lines.isEmpty();
    }

    /** The lines to send, in order. */
    List<LogLine> lines() {
        return lines;
    }

    /** The last stamped time in the batch: the event id a receiver resumes from. */
    String lastTime() {
        return lastTime;
    }

    void clear() {
        lines.clear();
        bytes = 0;
    }

    record LogLine(String time, String text) {
    }
}
