package com.playdata.calen.ledger.domain.model;

import java.time.LocalDateTime;

/** Finalization values contain serialized payloads, not provider/HTTP/JPA objects. */
public final class LedgerAiJobData {
    private LedgerAiJobData() {}
    public sealed interface Job permits AnalysisJob, ImageJob, ExcelJob {}
    public record AnalysisJob(Long ownerId, Long id) implements Job {}
    /** Reusing an image history requires rejecting responses from an earlier attempt. */
    public record ImageJob(Long ownerId, Long id, Long version) implements Job {
        public ImageJob(Long ownerId, Long id) { this(ownerId, id, null); }
    }
    /** The version read before inference must accompany every Excel terminal write. */
    public record ExcelJob(Long ownerId, String id, Long version) implements Job {}
    public record ImageText(String documentType, String rawText) {}
    public record Completion(Job job, String summary, String resultJson, ImageText image, LocalDateTime at) {}
    public record Failure(Job job, String errorMessage, String summary, LocalDateTime at) {}
    // Only image analysis exposes cancellation in the current API.
    public record Cancellation(ImageJob job, String summary, LocalDateTime at) {}
}
