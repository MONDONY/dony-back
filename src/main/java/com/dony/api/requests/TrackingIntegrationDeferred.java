package com.dony.api.requests;

/**
 * Intentional no-op marker class signalling that
 * {@code tracking/} integration with {@code package_requests} is DEFERRED.
 *
 * <p>See {@code TRACKING_INTEGRATION_DEFERRED.md} in the same package
 * for the design and re-activation plan.
 */
final class TrackingIntegrationDeferred {
    private TrackingIntegrationDeferred() {}
}
