package com.mesh.controlplane.store;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class AggregatedMetrics {

  private final AtomicInteger totalRequests = new AtomicInteger(0);
  private final AtomicInteger totalErrors = new AtomicInteger(0);
  private final AtomicLong totalLatency = new AtomicLong(0);

  public void add(int requests, int errors, long avgLatency) {
    totalRequests.addAndGet(requests);
    totalErrors.addAndGet(errors);
    totalLatency.addAndGet(avgLatency * requests); // weighted sum
  }

  public double getErrorRate() {
    int req = totalRequests.get();
    return req == 0 ? 0.0 : (double) totalErrors.get() / req * 100;
  }

  public int getTotalRequests() {
    return totalRequests.get();
  }

  public int getTotalErrors() {
    return totalErrors.get();
  }

  public long getAvgLatency() {
    int req = totalRequests.get();
    return req == 0 ? 0 : totalLatency.get() / req;
  }

  @Override
  public String toString() {
    return "AggregatedMetrics{requests=%d, errors=%d, errorRate=%.2f%%, avgLatency=%dms}"
        .formatted(totalRequests.get(), totalErrors.get(), getErrorRate(), getAvgLatency());
  }
}
