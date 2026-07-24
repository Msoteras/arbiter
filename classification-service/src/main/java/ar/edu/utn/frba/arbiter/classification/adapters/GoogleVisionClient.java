package ar.edu.utn.frba.arbiter.classification.adapters;

import ar.edu.utn.frba.arbiter.classification.config.GoogleVisionProperties;
import ar.edu.utn.frba.arbiter.classification.dto.WebImageMatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Calls Google Cloud Vision's Web Detection to find out whether a claim image is already
 * published somewhere on the web — a stock/catalog photo or a marketplace listing passed off
 * as the insured's own damaged item.
 *
 * <p>Unlike {@link ClipClient} (which compares against our own pgvector index and never leaves
 * the host), this integration sends the image to a third party. It stays disabled by default
 * and never breaks the classification: any failure degrades to {@link WebImageMatch#none()}.
 */
@Component
public class GoogleVisionClient {

    private static final Logger log = LoggerFactory.getLogger(GoogleVisionClient.class);

    private final RestClient restClient;
    private final GoogleVisionProperties properties;

    public GoogleVisionClient(GoogleVisionProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .build();
    }

    /** Whether the integration is configured to actually perform a web search. */
    public boolean isEnabled() {
        return properties.enabled() && properties.apiKey() != null && !properties.apiKey().isBlank();
    }

    public WebImageMatch detectWebMatches(String imageBase64) {
        if (!isEnabled()) {
            log.debug("[GoogleVision] Disabled or no API key — skipping web detection");
            return WebImageMatch.none();
        }

        long start = System.currentTimeMillis();
        Map<String, Object> request = Map.of("requests", List.of(Map.of(
                "image", Map.of("content", imageBase64),
                "features", List.of(Map.of("type", "WEB_DETECTION", "maxResults", properties.maxResults()))
        )));

        Map<String, Object> response = restClient.post()
                .uri(uri -> uri.path("/v1/images:annotate").queryParam("key", properties.apiKey()).build())
                .body(request)
                .retrieve()
                .body(Map.class);

        WebImageMatch match = parse(response);
        log.info("[GoogleVision] Web detection done in {} ms — full={} partial={} pages={} guess='{}'",
                System.currentTimeMillis() - start, match.fullMatches(), match.partialMatches(),
                match.pages().size(), match.bestGuessLabel());
        return match;
    }

    @SuppressWarnings("unchecked")
    private WebImageMatch parse(Map<String, Object> response) {
        List<Map<String, Object>> responses =
                response == null ? null : (List<Map<String, Object>>) response.get("responses");
        if (responses == null || responses.isEmpty()) {
            return WebImageMatch.none();
        }

        Map<String, Object> webDetection = (Map<String, Object>) responses.getFirst().get("webDetection");
        if (webDetection == null) {
            return WebImageMatch.none();
        }

        int fullMatches = sizeOf(webDetection.get("fullMatchingImages"));
        int partialMatches = sizeOf(webDetection.get("partialMatchingImages"));

        List<Map<String, Object>> rawPages =
                (List<Map<String, Object>>) webDetection.get("pagesWithMatchingImages");
        List<WebImageMatch.MatchedPage> pages = rawPages == null ? List.of() : rawPages.stream()
                .map(p -> new WebImageMatch.MatchedPage(
                        (String) p.get("url"),
                        (String) p.get("pageTitle")))
                .toList();

        List<Map<String, Object>> labels = (List<Map<String, Object>>) webDetection.get("bestGuessLabels");
        String bestGuess = labels == null || labels.isEmpty() ? null : (String) labels.getFirst().get("label");

        // visuallySimilarImages is intentionally NOT read — see WebImageMatch's javadoc.
        return new WebImageMatch(fullMatches, partialMatches, pages, bestGuess);
    }

    private int sizeOf(Object list) {
        return list instanceof List<?> l ? l.size() : 0;
    }
}
