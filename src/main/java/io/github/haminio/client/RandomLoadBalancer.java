package io.github.haminio.client;

import io.github.haminio.endpoint.Endpoint;
import io.github.haminio.endpoint.EndpointManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Random {@link LoadBalancer} over healthy endpoints.
 * Uses {@link ThreadLocalRandom} for thread-safe selection.
 */
public class RandomLoadBalancer implements LoadBalancer {

    private final EndpointManager endpointManager;

    public RandomLoadBalancer(EndpointManager endpointManager) {
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
        if (healthy.isEmpty()) {
            return null;
        }
        int idx = ThreadLocalRandom.current().nextInt(healthy.size());
        return healthy.get(idx);
    }
}
