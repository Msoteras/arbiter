package ar.edu.utn.frba.arbiter.reports.services;

import ar.edu.utn.frba.arbiter.reports.dto.MetricsFilter;
import ar.edu.utn.frba.arbiter.reports.dto.MetricsSummary;
import ar.edu.utn.frba.arbiter.reports.dto.ReportPeriod;
import ar.edu.utn.frba.arbiter.reports.dto.TimelineGranularity;
import ar.edu.utn.frba.arbiter.reports.dto.TimelinePoint;

import java.util.List;

/** The dashboard's stable figures for one period, whichever source answers. */
interface PeriodMetrics {

    MetricsSummary summary(ReportPeriod period, MetricsFilter filter);

    StableMetrics stable(ReportPeriod period, MetricsFilter filter);

    /** Non-empty buckets only; the caller fills the gaps. */
    List<TimelinePoint> timeline(ReportPeriod period, TimelineGranularity granularity, MetricsFilter filter);
}
