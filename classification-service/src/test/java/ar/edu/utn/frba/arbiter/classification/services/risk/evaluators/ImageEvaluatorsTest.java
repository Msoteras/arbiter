package ar.edu.utn.frba.arbiter.classification.services.risk.evaluators;

import ar.edu.utn.frba.arbiter.classification.services.risk.RiskContext;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskFactorEvaluator.Contribution;
import ar.edu.utn.frba.arbiter.common.dto.ImageForensicReport;
import ar.edu.utn.frba.arbiter.common.dto.ImageForensicReport.ImageFinding;
import ar.edu.utn.frba.arbiter.common.dto.ImageForensicReport.InternalMatch;
import ar.edu.utn.frba.arbiter.common.dto.ImageForensicReport.WebFinding;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ImageEvaluatorsTest {

    private final ImageReuseEvaluator reuse = new ImageReuseEvaluator();
    private final ImageWebMatchEvaluator web = new ImageWebMatchEvaluator();

    private static RiskContext ctx(ImageForensicReport report) {
        return new RiskContext(null, null, null, null, report);
    }

    private static ImageFinding finding(List<InternalMatch> internal, WebFinding webFinding) {
        return new ImageFinding("damage_photo-0", "foto.jpg", internal, webFinding);
    }

    // ── image_reuse ──────────────────────────────────────────────────────────

    @Test
    void reuse_notEvaluableWhenNoReport() {
        Contribution c = reuse.evaluate(ctx(null));
        assertThat(c.evaluable()).isFalse();
        assertThat(c.rationale()).contains("no evaluable");
    }

    @Test
    void reuse_evaluableZeroWhenAnalyzedButNoInternalMatches() {
        ImageForensicReport report = new ImageForensicReport(1, 1,
                List.of(finding(List.of(), new WebFinding(0, 0, List.of(), null))));
        Contribution c = reuse.evaluate(ctx(report));
        // Analyzed and found no reuse: a genuine low-risk 0, NOT "not evaluable".
        assertThat(c.evaluable()).isTrue();
        assertThat(c.score()).isZero();
    }

    @Test
    void reuse_scoresMaxSimilarity() {
        ImageForensicReport report = new ImageForensicReport(2, 0, List.of(
                finding(List.of(new InternalMatch(8734L, "a.jpg", 0.81)), null),
                finding(List.of(new InternalMatch(9002L, "b.jpg", 0.96)), null)));

        Contribution c = reuse.evaluate(ctx(report));

        assertThat(c.score()).isEqualTo(0.96);
        assertThat(c.rationale()).contains("96%");
    }

    // ── image_web_match ──────────────────────────────────────────────────────

    @Test
    void web_notEvaluableWhenNoSearchPerformed() {
        ImageForensicReport report = new ImageForensicReport(1, 0,
                List.of(finding(List.of(), null)));
        Contribution c = web.evaluate(ctx(report));
        assertThat(c.evaluable()).isFalse();
        assertThat(c.rationale()).contains("no evaluable");
    }

    @Test
    void web_evaluableZeroWhenSearchedButNothingFound() {
        ImageForensicReport report = new ImageForensicReport(1, 1,
                List.of(finding(List.of(), new WebFinding(0, 0, List.of(), null))));
        Contribution c = web.evaluate(ctx(report));
        // Searched and found nothing on the web: a genuine low-risk 0, NOT "not evaluable".
        assertThat(c.evaluable()).isTrue();
        assertThat(c.score()).isZero();
    }

    @Test
    void web_exactMatchScoresHigh() {
        ImageForensicReport report = new ImageForensicReport(1, 1, List.of(
                finding(List.of(), new WebFinding(1, 0, List.of(new WebFinding.Page("u", "t")), "nike"))));

        Contribution c = web.evaluate(ctx(report));

        assertThat(c.score()).isGreaterThanOrEqualTo(0.9);
    }

    @Test
    void web_partialMatchesScoreLowerThanExact() {
        WebFinding partialOnly = new WebFinding(0, 3,
                List.of(new WebFinding.Page("u1", "t1"), new WebFinding.Page("u2", "t2")), "iphone");
        ImageForensicReport report = new ImageForensicReport(1, 1, List.of(finding(List.of(), partialOnly)));

        double score = web.evaluate(ctx(report)).score();

        assertThat(score).isGreaterThan(0.0).isLessThan(0.9);
    }

    @Test
    void web_scoreNeverExceedsOne() {
        WebFinding heavy = new WebFinding(5, 20,
                List.of(new WebFinding.Page("u", "t"), new WebFinding.Page("u", "t"),
                        new WebFinding.Page("u", "t"), new WebFinding.Page("u", "t")), "x");
        ImageForensicReport report = new ImageForensicReport(1, 1, List.of(finding(List.of(), heavy)));

        assertThat(web.evaluate(ctx(report)).score()).isLessThanOrEqualTo(1.0);
    }
}
