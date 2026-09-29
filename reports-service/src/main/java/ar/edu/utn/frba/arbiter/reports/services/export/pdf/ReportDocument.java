package ar.edu.utn.frba.arbiter.reports.services.export.pdf;

import ar.edu.utn.frba.arbiter.reports.dto.ReportBranding;

import java.util.List;

public record ReportDocument(
        String title,
        String periodLabel,
        String code,
        ReportBranding branding,
        String confidentialityNote,
        List<Block> blocks
) {}
