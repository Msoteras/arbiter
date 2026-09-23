package ar.edu.utn.frba.arbiter.reports.dto;

public enum ReportFormat {
    CSV("text/csv;charset=UTF-8", "csv"),
    PDF("application/pdf", "pdf");

    private final String mediaType;
    private final String extension;

    ReportFormat(String mediaType, String extension) {
        this.mediaType = mediaType;
        this.extension = extension;
    }

    public String mediaType() {
        return mediaType;
    }

    public String extension() {
        return extension;
    }
}
