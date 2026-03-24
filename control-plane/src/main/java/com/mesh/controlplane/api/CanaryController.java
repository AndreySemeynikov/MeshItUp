package com.mesh.controlplane.api;

import com.mesh.controlplane.canary.CanaryManager;
import com.mesh.controlplane.model.CanaryStartRequest;
import com.mesh.controlplane.model.CanaryState;
import com.mesh.controlplane.model.CanaryState.Status;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/canary")
@Tag(name = "Canary", description = "Canary release management API")
public class CanaryController {

  public static final String STATUS = "status";
  public static final String SERVICE_ID = "serviceId";
  public static final String NEW_VERSION = "newVersion";

  private final CanaryManager canaryManager;

  private static final String ERROR = "error";

  public CanaryController(CanaryManager canaryManager) {
    this.canaryManager = canaryManager;
  }

  @PostMapping("/start")
  @Operation(summary = "Start a canary release")
  public ResponseEntity<?> start(@RequestBody CanaryStartRequest request) {
    try {
      CanaryState state = canaryManager.start(request);
      return ResponseEntity.ok(buildStateResponse(state));
    } catch (CanaryManager.CanaryConflictException e) {
      return ResponseEntity.status(409).body(Map.of(ERROR, e.getMessage()));
    } catch (CanaryManager.ServiceNotFoundException e) {
      return ResponseEntity.status(404).body(Map.of(ERROR, e.getMessage()));
    } catch (Exception e) {
      return ResponseEntity.internalServerError().body(Map.of(ERROR, e.getMessage()));
    }
  }

  @GetMapping("/status")
  @Operation(summary = "Get current canary status")
  public ResponseEntity<?> status() {
    CanaryState state = canaryManager.getState();
    return ResponseEntity.ok(buildStateResponse(state));
  }

  @PostMapping("/promote")
  @Operation(summary = "Manually promote canary to stable")
  public ResponseEntity<?> promote() {
    try {
      CanaryState state = canaryManager.promote();
      Map<String, Object> response = new LinkedHashMap<>();
      response.put(STATUS, state.getStatus().name());
      response.put(SERVICE_ID, state.getServiceId());
      response.put(NEW_VERSION, state.getCanaryVersion());
      return ResponseEntity.ok(response);
    } catch (CanaryManager.CanaryConflictException e) {
      return ResponseEntity.status(409).body(Map.of(ERROR, e.getMessage()));
    } catch (Exception e) {
      return ResponseEntity.internalServerError().body(Map.of(ERROR, e.getMessage()));
    }
  }

  @PostMapping("/rollback")
  @Operation(summary = "Manually rollback canary")
  public ResponseEntity<?> rollback() {
    try {
      CanaryState state = canaryManager.rollback();
      Map<String, Object> response = new LinkedHashMap<>();
      response.put(STATUS, state.getStatus().name());
      response.put(SERVICE_ID, state.getServiceId());
      return ResponseEntity.ok(response);
    } catch (CanaryManager.CanaryConflictException e) {
      return ResponseEntity.status(409).body(Map.of(ERROR, e.getMessage()));
    } catch (Exception e) {
      return ResponseEntity.internalServerError().body(Map.of(ERROR, e.getMessage()));
    }
  }

  private Map<String, Object> buildStateResponse(CanaryState state) {
    Map<String, Object> response = new LinkedHashMap<>();
    response.put(STATUS, state.getStatus().name());

    if (state.getStatus() == Status.IDLE) {
      return response;
    }

    response.put(SERVICE_ID, state.getServiceId());
    if (state.getStableVersion() != null) response.put("stableVersion", state.getStableVersion());
    if (state.getCanaryVersion() != null) response.put("canaryVersion", state.getCanaryVersion());
    response.put("currentWeight", state.getCurrentWeight());
    response.put("weightStep", state.getWeightStep());
    response.put("errorThreshold", state.getErrorThreshold());
    response.put("consecutiveSuccessCount", state.getConsecutiveSuccessCount());
    if (state.getStartedAt() != null) response.put("startedAt", state.getStartedAt().toString());
    if (state.getLastEvaluationAt() != null)
      response.put("lastEvaluationAt", state.getLastEvaluationAt().toString());
    if (state.getLastEvaluationResult() != null)
      response.put("lastEvaluationResult", state.getLastEvaluationResult());

    return response;
  }
}
