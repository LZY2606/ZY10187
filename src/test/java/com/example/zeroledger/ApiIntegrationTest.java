package com.example.zeroledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.zeroledger.service.FixtureService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class ApiIntegrationTest {
    @Autowired
    MockMvc mockMvc;
    @Autowired
    FixtureService fixtureService;
    @Autowired
    ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        fixtureService.reset();
    }

    @Test
    void homePageShowsChineseTitle() throws Exception {
        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("风洞零线帐"));
    }

    @Test
    void stateExposesFixedFixtureAndDiagnostics() throws Exception {
        mockMvc.perform(get("/api/state").param("runId", "RUN-20260921-A"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysis.config.driftMode").value("LINEAR"))
                .andExpect(jsonPath("$.analysis.samples[2].coefficients[0]").doesNotExist())
                .andExpect(jsonPath("$.analysis.samples[2].diagnostics")
                        .value(org.hamcrest.Matchers.hasItem("DYNAMIC_PRESSURE_NONPOSITIVE")))
                .andExpect(jsonPath("$.analysis.samples[4].diagnostics")
                        .value(org.hamcrest.Matchers.hasItem("TARE_EXTRAPOLATED")));
    }

    @Test
    void rejectsTareAndSwitchesPiecewiseDrift() throws Exception {
        mockMvc.perform(patch("/api/samples/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tareAccepted\":false}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/runs/RUN-20260921-A/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"driftMode":"PIECEWISE",
                                 "rotationOrder":"BETA_THEN_ALPHA_YZ",
                                 "calibrationId":"v2023-initial"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.config.driftMode").value("PIECEWISE"));
        mockMvc.perform(get("/api/state").param("runId", "RUN-20260921-A"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysis.config.calibrationId").value("v2023-initial"))
                .andExpect(jsonPath("$.analysis.driftModel.acceptedTareCount").value(1))
                .andExpect(jsonPath("$.analysis.samples[1].diagnostics")
                        .value(org.hamcrest.Matchers.hasItem("TARE_EXTRAPOLATED")));
    }

    @Test
    void exportThenClearAndImportReplaysSameState() throws Exception {
        MvcResult exported = mockMvc.perform(get("/api/export"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode before = objectMapper.readTree(exported.getResponse().getContentAsString());
        String beforeCoefficient = stateCoefficientAfterImport(before.toString());
        mockMvc.perform(post("/api/admin/reset-fixture")).andExpect(status().isOk());
        String afterCoefficient = stateCoefficientAfterImport(before.toString());
        assertThat(afterCoefficient).isEqualTo(beforeCoefficient);
    }

    private String stateCoefficientAfterImport(String bundle) throws Exception {
        mockMvc.perform(post("/api/import").contentType(MediaType.APPLICATION_JSON).content(bundle))
                .andExpect(status().isOk());
        MvcResult state = mockMvc.perform(get("/api/state").param("runId", "RUN-20260921-A"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = objectMapper.readTree(state.getResponse().getContentAsString());
        return json.at("/analysis/samples/1/coefficients/4").asText();
    }
}
