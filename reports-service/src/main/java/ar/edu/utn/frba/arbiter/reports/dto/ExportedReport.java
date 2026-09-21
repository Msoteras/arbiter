package ar.edu.utn.frba.arbiter.reports.dto;

/** A report rendered to a file, ready to hand to the browser as a download. */
public record ExportedReport(String filename, ReportFormat format, byte[] content) {}
