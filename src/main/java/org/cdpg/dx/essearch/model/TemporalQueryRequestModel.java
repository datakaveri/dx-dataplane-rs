package org.cdpg.dx.essearch.model;

public class TemporalQueryRequestModel {
    private String timeRel;
    private String time;
    private String endTime;
    private String timeLimit;
    private int size;
    private int page;

    public String getTimeRel() {
        return timeRel;
    }
    public void setTimeRel(String timeRel) {
        this.timeRel = timeRel;
    }
    public String getTime() {
        return time;
    }
    public void setTime(String time) {
        this.time = time;
    }
    public String getEndTime() {
        return endTime;
    }
    public void setEndTime(String endTime) {
        this.endTime = endTime;
    }
    public String getTimeLimit() {
        return timeLimit;
    }
    public void setTimeLimit(String timeLimit) {
        this.timeLimit = timeLimit;
    }

    public int getSize() {
        return size;
    }

    @Override
    public String toString() {
        return "TemporalQueryRequestModel{" +
                "timeRel='" + timeRel + '\'' +
                ", time='" + time + '\'' +
                ", endTime='" + endTime + '\'' +
                ", timeLimit='" + timeLimit + '\'' +
                ", size=" + size +
                ", page=" + page +
                '}';
    }

    public TemporalQueryRequestModel(String timeRel, String time, String endTime, String timeLimit, int size, int page) {
        this.timeRel = timeRel;
        this.time = time;
        this.endTime = endTime;
        this.timeLimit = timeLimit;
        this.size = size;
        this.page = page;
    }

    public void setSize(int size) {
        this.size = size;
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }
}

