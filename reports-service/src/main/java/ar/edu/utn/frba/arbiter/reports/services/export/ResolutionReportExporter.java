package ar.edu.utn.frba.arbiter.reports.services.export;

import ar.edu.utn.frba.arbiter.reports.dto.ReportFormat;
import ar.edu.utn.frba.arbiter.reports.dto.ResolutionReport;

/** Renders the resolution report to one file format. One implementation per {@link ReportFormat}. */
public interface ResolutionReportExporter {

    ReportFormat format();

    byte[] export(ResolutionReport report);
}
