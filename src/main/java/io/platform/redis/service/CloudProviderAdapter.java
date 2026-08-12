package io.platform.redis.service;

import io.platform.redis.domain.entity.RedisInstance;

/**
 * Cloud Provider Adapter: abstracts cloud infrastructure operations.
 *
 * The key design principle:
 *   Express WHAT you need (private connectivity, encrypted storage)
 *   NOT HOW to do it (AWS PrivateLink, gp3 EBS)
 *
 * Each cloud implementation maps:
 *   - provisionNetwork → AWS: VPC + PrivateLink, GCP: VPC + PSC
 *   - provisionStorage → AWS: gp3 EBS, GCP: pd-ssd
 *   - provisionIdentity → AWS: IAM Role, GCP: Service Account
 */
public interface CloudProviderAdapter {

    void provisionNetwork(RedisInstance instance);
    void deleteNetwork(RedisInstance instance);

    void provisionStorage(RedisInstance instance);
    void deleteStorage(RedisInstance instance);

    void deployRedis(RedisInstance instance);
    void deleteRedis(RedisInstance instance);

    void configureRedis(RedisInstance instance);

    /**
     * Scales a Redis instance (changes node type, shard count, or replicas).
     * Triggered when memory or availability changes.
     */
    void scaleRedis(RedisInstance instance);

    /**
     * Modifies Redis configuration (TLS, encryption, persistence settings).
     * Triggered when non-topology fields change.
     */
    void modifyRedis(RedisInstance instance);

    boolean checkHealth(RedisInstance instance);

    String providerName();
}
