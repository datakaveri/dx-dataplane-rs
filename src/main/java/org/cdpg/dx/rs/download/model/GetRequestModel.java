package org.cdpg.dx.rs.download.model;

public record GetRequestModel(String id, int size, int page, String time, String endTime, String timeRel, String sortBy, String sortOrder) {
    @Override
    public String toString() {
        return "GetRequestModel{" +
                "id='" + id + '\'' +
                ", size=" + size +
                ", page=" + page +
                ", time='" + time + '\'' +
                ", endTime='" + endTime + '\'' +
                ", timeRel='" + timeRel + '\'' +
                ", sortBy='" + sortBy + '\'' +
                ", sortOrder='" + sortOrder + '\'' +
                '}';
    }
}
