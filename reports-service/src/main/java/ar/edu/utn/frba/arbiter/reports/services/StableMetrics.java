package ar.edu.utn.frba.arbiter.reports.services;

import ar.edu.utn.frba.arbiter.reports.dto.FastTrackImpact;
import ar.edu.utn.frba.arbiter.reports.dto.FraudDetection;
import ar.edu.utn.frba.arbiter.reports.dto.LegalDeadline;
import ar.edu.utn.frba.arbiter.reports.dto.MetricCount;
import ar.edu.utn.frba.arbiter.reports.dto.MetricsSummary;
import ar.edu.utn.frba.arbiter.reports.dto.RecommendationAgreement;
import ar.edu.utn.frba.arbiter.reports.dto.ReopeningRate;
import ar.edu.utn.frba.arbiter.reports.dto.SettledAmounts;

import java.util.List;

/**
 * The part of the dashboard that stops moving once its period ends, and so the part that can be
 * stored. Everything else in {@code ClaimMetrics} describes the claims as they are now.
 */
record StableMetrics(
        MetricsSummary summary,
        RecommendationAgreement agreement,
        LegalDeadline legalDeadline,
        ReopeningRate reopening,
        SettledAmounts settled,
        FraudDetection fraud,
        FastTrackImpact fastTrack,
        List<MetricCount> byBranch
) {}
