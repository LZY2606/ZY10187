package com.example.zeroledger.web;

import com.example.zeroledger.service.ExportBundle;
import com.example.zeroledger.service.StateService;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class StateController {
    private final StateService stateService;

    public StateController(StateService stateService) {
        this.stateService = stateService;
    }

    @GetMapping("/state")
    public Map<String, Object> state(@RequestParam(required = false) String runId) {
        return stateService.state(runId);
    }

    @PatchMapping("/samples/{sampleId}")
    public Map<String, Object> setTareAccepted(@PathVariable long sampleId,
                                               @RequestBody Map<String, Boolean> request) {
        Boolean accepted = request.get("tareAccepted");
        if (accepted == null) {
            throw new IllegalArgumentException("缺少 tareAccepted");
        }
        stateService.setTareAccepted(sampleId, accepted);
        return Map.of("ok", true);
    }

    @PostMapping("/runs/{runId}/config")
    public Map<String, Object> saveConfig(@PathVariable String runId,
                                          @RequestBody StateService.ConfigRequest request) {
        return Map.of("config", stateService.saveConfig(runId, request));
    }

    @GetMapping(value = "/export", produces = MediaType.APPLICATION_JSON_VALUE)
    public ExportBundle export() {
        return stateService.exportAll();
    }

    @PostMapping("/import")
    public Map<String, Object> importBundle(@RequestBody ExportBundle bundle) {
        stateService.importBundle(bundle);
        return Map.of("ok", true);
    }

    @PostMapping("/admin/reset-fixture")
    public Map<String, Object> resetFixture() {
        stateService.resetFixture();
        return Map.of("ok", true);
    }
}
