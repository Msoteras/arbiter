package ar.edu.utn.frba.arbiter.reports.services.export;

import ar.edu.utn.frba.arbiter.reports.dto.FraudReport;
import ar.edu.utn.frba.arbiter.reports.dto.ReportFormat;

/** Renders the fraud report to one file format. One implementation per {@link ReportFormat}. */
public interface FraudReportExporter {

    ReportFormat format();

    byte[] export(FraudReport report);
}
