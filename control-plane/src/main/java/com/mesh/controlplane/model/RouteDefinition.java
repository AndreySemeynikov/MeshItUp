package com.mesh.controlplane.model;

import java.util.List;

public record RouteDefinition(String source, String pathPattern, List<Destination> destinations) {}
