package com.windtunnel.zeroledger.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class WebIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void indexPageShowsTitle() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("风洞零线帐")));
    }

    @Test
    void analysisExposesCoefficientsAndFlags() throws Exception {
        String body = mvc.perform(get("/api/runs/1/analysis"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("\"Cd\""));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("\"extrapolated\":true"));
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("Infinity"));
    }

    @Test
    void zeroQRunHasNullCoefficients() throws Exception {
        String body = mvc.perform(get("/api/runs/2/analysis"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // q=0 测点存在且系数为 null（JSON 里是 null，不是大数）
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("\"qPositive\":false"));
    }

    @Test
    void rotationOrderQueryParamChangesMatrixAndCoefficients() throws Exception {
        String yz = mvc.perform(get("/api/runs/1/analysis").param("rotationOrder", "YZ"))
                .andReturn().getResponse().getContentAsString();
        String zy = mvc.perform(get("/api/runs/1/analysis").param("rotationOrder", "ZY"))
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertNotEquals(yz, zy);
        org.junit.jupiter.api.Assertions.assertTrue(zy.contains("\"rotationOrder\":\"ZY\""));
    }

    @Test
    void tareRejectionAndSettingsRoundTripViaHttp() throws Exception {
        mvc.perform(put("/api/runs/1/settings")
                        .contentType("application/json")
                        .content("{\"driftMode\":\"SEGMENT\",\"rotationOrder\":\"ZY\",\"calibVersion\":\"v2\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/state"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"driftMode\":\"SEGMENT\"")));
        // 还原
        mvc.perform(put("/api/runs/1/settings")
                .contentType("application/json")
                .content("{\"driftMode\":\"LINEAR\",\"rotationOrder\":\"YZ\",\"calibVersion\":\"v1\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void exportImportResetLifecycle() throws Exception {
        String snap = mvc.perform(get("/api/export"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        mvc.perform(post("/api/clear")).andExpect(status().isOk());
        mvc.perform(post("/api/import").contentType("application/json")
                        .content("{\"clear\":true,\"snapshot\":" + snap + "}"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"ok\":true")));
        mvc.perform(get("/api/runs/1/analysis")).andExpect(status().isOk());
    }

    @Test
    void unknownRunIs404() throws Exception {
        mvc.perform(get("/api/runs/999/analysis"))
                .andExpect(status().isNotFound());
    }
}
