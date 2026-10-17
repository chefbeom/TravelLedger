package com.playdata.calen.travel.domain.model;

import java.util.List;

public final class TravelRouteUploadData {
    private TravelRouteUploadData() {}
    public record Candidate(String originalFileName,String contentType,long fileSize) {}
    public record Completed(String objectKey,String originalFileName,String contentType,long fileSize) {
        @Override public String toString(){return "Completed[objectKey=<redacted>, metadata=<redacted>]";}
    }
    public record Target(String method,String uploadUrl,String objectKey,String storedFileName,String originalFileName,String contentType,long fileSize) {
        @Override public String toString(){return "Target[uploadUrl=<redacted>, objectKey=<redacted>, metadata=<redacted>]";}
    }
    public record Prepared(String uploadMode,List<Target> uploads) {}
}
