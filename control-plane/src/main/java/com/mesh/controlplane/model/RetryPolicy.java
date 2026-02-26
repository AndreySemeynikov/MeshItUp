package com.mesh.controlplane.model;

import java.util.List;

public record RetryPolicy(int maxAttempts, long delayMs, List<Integer> retriableStatusCodes) {}
