package ar.edu.utn.frba.arbiter.reports.dto;

public record ExportedReport(String filename, ReportFormat format, byte[] content) {}
