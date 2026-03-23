package io.github.haminio.client;

import io.github.haminio.endpoint.Endpoint;
import io.github.haminio.endpoint.EndpointManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Round-robin {@link LoadBalancer} implementation.
 * Delegates health awareness to {@link EndpointManager}.
 * Does NOT manage connections — all connection reuse is via OkHttp's ConnectionPool.
 */
public class RoundRobinLoadBalancer implements LoadBalancer {

    private final EndpointManager endpointManager;
    private final AtomicInteger counter = new AtomicInteger(0);

    public RoundRobinLoadBalancer(EndpointManager endpointManager) {
        this.endpointManager = endpointManager;
    }

    @Override
    public Endpoint next() {
        return next(List.of());
    }

    @Override
    public Endpoint next(List<Endpoint> exclude) {
        List<Endpoint> healthy = new ArrayList<>(endpointManager.healthyEndpoints());
        healthy.removeAll(exclude);
        if (healthy.isEmpty()) return null;
        int idx = Math.abs(counter.getAndIncrement() % healthy.size());
        return healthy.get(idx);
    }
}
