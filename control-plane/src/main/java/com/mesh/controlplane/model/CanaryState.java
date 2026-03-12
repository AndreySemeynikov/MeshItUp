package com.mesh.controlplane.model;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.Map;

@Setter
@Getter
public class CanaryState {

  public enum Status {
    IDLE,
    IN_PROGRESS,
    PROMOTED,
    ROLLED_BACK
  }

  private String serviceId;
  private Status status = Status.IDLE;
  private String stableVersion;
  private String canaryVersion;
  private String canaryImage;
  private Map<String, String> canaryEnv;
  private int currentWeight;
  private int weightStep;
  private double errorThreshold;
  private int consecutiveSuccessCount;
  private Instant startedAt;
  private Instant lastEvaluationAt;
  private String lastEvaluationResult;
  private int targetWeight;
  private int stableReplicasAtStart;

  // --- Getters & Setters ---

  /** Reset to IDLE state, clearing all canary-related fields. */
  public void reset() {
    this.serviceId = null;
    this.status = Status.IDLE;
    this.stableVersion = null;
    this.canaryVersion = null;
    this.canaryImage = null;
    this.canaryEnv = null;
    this.currentWeight = 0;
    this.weightStep = 0;
    this.errorThreshold = 0;
    this.consecutiveSuccessCount = 0;
    this.startedAt = null;
    this.lastEvaluationAt = null;
    this.lastEvaluationResult = null;
    this.targetWeight = 0;
    this.stableReplicasAtStart = 0;
  }
}
