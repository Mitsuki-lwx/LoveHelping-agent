package cn.lwx.lwxaiagent.phase0;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
public class ConversationTracker {

    private static final CopyOnWriteArrayList<ConversationRecord> records = new CopyOnWriteArrayList<>();
    private static final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .enable(SerializationFeature.INDENT_OUTPUT);

    @Data
    public static class ConversationRecord {
        private int id;
        private String query;
        private String category;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private long durationMs;
        private String response;
        private int steps;
        private boolean toolsCalled;
        private boolean retrievalCalled;
        private boolean success;
        private String errorMessage;
    }

    public static TrackerSession begin(String query, String category) {
        return new TrackerSession(query, category);
    }

    public static class TrackerSession {
        private final ConversationRecord record;

        TrackerSession(String query, String category) {
            this.record = new ConversationRecord();
            this.record.setQuery(query);
            this.record.setCategory(category);
            this.record.setStartTime(LocalDateTime.now());
        }

        public void complete(String response, int steps, boolean toolsCalled, boolean retrievalCalled) {
            this.record.setEndTime(LocalDateTime.now());
            this.record.setDurationMs(Duration.between(record.getStartTime(), record.getEndTime()).toMillis());
            this.record.setResponse(response != null ? truncate(response, 500) : "");
            this.record.setSteps(steps);
            this.record.setToolsCalled(toolsCalled);
            this.record.setRetrievalCalled(retrievalCalled);
            this.record.setSuccess(true);
            this.record.setId(records.size() + 1);
            records.add(this.record);
            log.info("[CONV#{}] {}ms | tools={} | retrieval={} | query={}",
                    this.record.getId(), this.record.getDurationMs(),
                    toolsCalled, retrievalCalled, truncate(this.record.getQuery(), 80));
        }

        public void fail(String errorMessage) {
            this.record.setEndTime(LocalDateTime.now());
            this.record.setDurationMs(Duration.between(record.getStartTime(), record.getEndTime()).toMillis());
            this.record.setResponse("");
            this.record.setSuccess(false);
            this.record.setErrorMessage(errorMessage);
            this.record.setId(records.size() + 1);
            records.add(this.record);
            log.warn("[CONV#{}] FAILED | {}ms | error={}", this.record.getId(),
                    this.record.getDurationMs(), errorMessage);
        }
    }

    public static void saveReport() throws Exception {
        File output = Paths.get("target", "phase0-report.json").toFile();
        mapper.writeValue(output, records);
        log.info("Phase 0 report saved to {} ({} records)", output.getAbsolutePath(), records.size());
        printSummary();
    }

    private static void printSummary() {
        int total = records.size();
        int success = (int) records.stream().filter(ConversationRecord::isSuccess).count();
        int failed = total - success;
        int withTools = (int) records.stream().filter(ConversationRecord::isToolsCalled).count();
        int withRetrieval = (int) records.stream().filter(ConversationRecord::isRetrievalCalled).count();

        long totalDuration = records.stream().mapToLong(ConversationRecord::getDurationMs).sum();
        double avgDuration = total > 0 ? (double) totalDuration / total : 0;
        long maxDuration = records.stream().mapToLong(ConversationRecord::getDurationMs).max().orElse(0);
        long minDuration = records.stream().mapToLong(ConversationRecord::getDurationMs).min().orElse(0);

        List<Long> sortedDurations = records.stream()
                .mapToLong(ConversationRecord::getDurationMs)
                .sorted()
                .boxed()
                .toList();
        long p50 = percentile(sortedDurations, 50);
        long p90 = percentile(sortedDurations, 90);
        long p99 = percentile(sortedDurations, 99);

        log.info("===== Phase 0 Summary =====");
        log.info("Total conversations: {}", total);
        log.info("Success: {}, Failed: {}", success, failed);
        log.info("Success rate: {}%", Math.round((double) success / total * 100));
        log.info("Tool calls: {} ({}%)", withTools, Math.round((double) withTools / total * 100));
        log.info("Retrieval calls: {} ({}%)", withRetrieval, Math.round((double) withRetrieval / total * 100));
        log.info("Latency: avg={}ms, min={}ms, max={}ms", Math.round(avgDuration), minDuration, maxDuration);
        log.info("Latency distribution: p50={}ms, p90={}ms, p99={}ms", p50, p90, p99);
    }

    private static long percentile(List<Long> sorted, int pct) {
        if (sorted.isEmpty()) return 0;
        int idx = (int) Math.ceil(pct / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
