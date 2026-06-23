package ar.edu.utn.frba.arbiter.siniestros.services;

import ar.edu.utn.frba.arbiter.siniestros.adapters.AseguradoraAdapter;
import ar.edu.utn.frba.arbiter.siniestros.adapters.ReglasAdapter;
import ar.edu.utn.frba.arbiter.siniestros.adapters.SiniestroClassifier;
import ar.edu.utn.frba.arbiter.siniestros.dto.*;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ClasificacionOrquestador {

    private static final Logger log = LoggerFactory.getLogger(ClasificacionOrquestador.class);

    private final SiniestroClassifier classifier;
    private final ReglasAdapter rulesAdapter;
    private final AseguradoraAdapter insurerAdapter;

    public ClasificacionResponse classify(DenunciaSiniestro claim) {
        log.info("[Orchestrator] Starting classification — policy='{}' insuredId='{}' branch='{}' claimCause='{}'",
                claim.policyNumber(), claim.insuredId(), claim.branch(), claim.claimCause());

        log.debug("[Orchestrator] Fetching policy '{}'...", claim.policyNumber());
        PolizaAsegurado policy = insurerAdapter.getPolicy(claim.policyNumber());
        log.info("[Orchestrator] Policy OK — insured='{}' upToDate={} insuredAmount={}",
                policy.insuredName(), policy.upToDate(), policy.insuredAmount());

        log.debug("[Orchestrator] Fetching history for insuredId '{}'...", claim.insuredId());
        HistorialAsegurado history = insurerAdapter.getHistory(claim.insuredId());
        log.info("[Orchestrator] History OK — previous_claims={} total_amount_claimed={}",
                history.previousClaimsCount(), history.totalAmountClaimed());

        log.debug("[Orchestrator] Fetching rules — branch='{}' claimCause='{}'...",
                claim.branch(), claim.claimCause());
        ReglasNegocio rules = rulesAdapter.getRules(claim.branch(), claim.claimCause());
        log.info("[Orchestrator] Rules OK — {} rules, {} exclusions, {} fast-track criteria",
                rules.rules().size(), rules.exclusions().size(), rules.fastTrackCriteria().size());

        log.info("[Orchestrator] Building prompt and sending to LLM...");
        ClasificacionRequest request = buildRequest(claim, policy, history, rules);
        ClasificacionResponse response = classifier.classify(request);

        log.info("[Orchestrator] Classification done — result={} confidence={}",
                response.classification(), response.confidence());
        return response;
    }

    private ClasificacionRequest buildRequest(
            DenunciaSiniestro claim,
            PolizaAsegurado policy,
            HistorialAsegurado history,
            ReglasNegocio rules
    ) {
        var promptBuilder = new PromptBuilder()
                .withRules(rules)
                .withPolicy(policy)
                .withHistory(history);

        return ClasificacionRequest.builder()
                .branch(claim.branch())
                .product(claim.product())
                .claimCause(claim.claimCause())
                .insuredItem(claim.insuredItem())
                .description(claim.description())
                .attachmentsOcr(claim.attachmentsOcr())
                .insurerRules(promptBuilder.buildRulesAndPolicy())
                .insuredHistory(promptBuilder.buildHistory())
                .build();
    }
}
