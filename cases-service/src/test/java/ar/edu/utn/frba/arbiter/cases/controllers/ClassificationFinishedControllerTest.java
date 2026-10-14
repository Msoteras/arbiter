package ar.edu.utn.frba.arbiter.cases.controllers;

import ar.edu.utn.frba.arbiter.cases.exceptions.CaseExceptionHandler;
import ar.edu.utn.frba.arbiter.cases.exceptions.CaseNotFoundException;
import ar.edu.utn.frba.arbiter.cases.services.ClassificationOutcomeService;
import ar.edu.utn.frba.arbiter.common.dto.ClassificationFinished.Outcome;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ClassificationFinishedController.class)
@Import(CaseExceptionHandler.class)
class ClassificationFinishedControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ClassificationOutcomeService classificationOutcomeService;

    @Test
    void notice_returns204_andHandsTheOutcomeOver() throws Exception {
        mockMvc.perform(post("/api/v1/cases/42/classification-finished")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"outcome": "FAILED"}
                                """))
                .andExpect(status().isNoContent());

        verify(classificationOutcomeService).onClassificationFinished(42L, Outcome.FAILED);
    }

    @Test
    void noticeWithoutOutcome_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/cases/42/classification-finished")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(classificationOutcomeService);
    }

    @Test
    void noticeForAnUnknownCase_returns404() throws Exception {
        doThrow(new CaseNotFoundException(42L))
                .when(classificationOutcomeService).onClassificationFinished(any(), any());

        mockMvc.perform(post("/api/v1/cases/42/classification-finished")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"outcome": "COMPLETED"}
                                """))
                .andExpect(status().isNotFound());
    }
}
