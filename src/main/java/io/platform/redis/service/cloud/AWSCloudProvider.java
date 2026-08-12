package io.platform.redis.service.cloud;

import io.platform.redis.domain.entity.RedisInstance;
import io.platform.redis.domain.enums.NetworkAccess;
import io.platform.redis.service.CloudProviderAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.AttributeBooleanValue;
import software.amazon.awssdk.services.ec2.model.AuthorizeSecurityGroupIngressRequest;
import software.amazon.awssdk.services.ec2.model.CreateSecurityGroupRequest;
import software.amazon.awssdk.services.ec2.model.CreateSecurityGroupResponse;
import software.amazon.awssdk.services.ec2.model.CreateSubnetRequest;
import software.amazon.awssdk.services.ec2.model.CreateSubnetResponse;
import software.amazon.awssdk.services.ec2.model.CreateVpcRequest;
import software.amazon.awssdk.services.ec2.model.CreateVpcResponse;
import software.amazon.awssdk.services.ec2.model.DeleteSecurityGroupRequest;
import software.amazon.awssdk.services.ec2.model.DescribeSecurityGroupsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeSecurityGroupsResponse;
import software.amazon.awssdk.services.ec2.model.DescribeSubnetsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeSubnetsResponse;
import software.amazon.awssdk.services.ec2.model.DescribeVpcsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeVpcsResponse;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.IpPermission;
import software.amazon.awssdk.services.ec2.model.IpRange;
import software.amazon.awssdk.services.ec2.model.ModifyVpcAttributeRequest;
import software.amazon.awssdk.services.ec2.model.ResourceType;
import software.amazon.awssdk.services.ec2.model.Subnet;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.TagSpecification;
import software.amazon.awssdk.services.elasticache.ElastiCacheClient;
import software.amazon.awssdk.services.elasticache.model.CacheSubnetGroupAlreadyExistsException;
import software.amazon.awssdk.services.elasticache.model.CacheSubnetGroupNotFoundException;
import software.amazon.awssdk.services.elasticache.model.CreateCacheSubnetGroupRequest;
import software.amazon.awssdk.services.elasticache.model.CreateReplicationGroupRequest;
import software.amazon.awssdk.services.elasticache.model.DeleteCacheSubnetGroupRequest;
import software.amazon.awssdk.services.elasticache.model.DeleteReplicationGroupRequest;
import software.amazon.awssdk.services.elasticache.model.DescribeReplicationGroupsRequest;
import software.amazon.awssdk.services.elasticache.model.DescribeReplicationGroupsResponse;
import software.amazon.awssdk.services.elasticache.model.ReplicationGroup;
import software.amazon.awssdk.services.elasticache.model.ReplicationGroupNotFoundException;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.route53.model.Change;
import software.amazon.awssdk.services.route53.model.ChangeAction;
import software.amazon.awssdk.services.route53.model.ChangeBatch;
import software.amazon.awssdk.services.route53.model.ChangeResourceRecordSetsRequest;
import software.amazon.awssdk.services.route53.model.RRType;
import software.amazon.awssdk.services.route53.model.ResourceRecord;
import software.amazon.awssdk.services.route53.model.ResourceRecordSet;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.CreateSecretRequest;
import software.amazon.awssdk.services.secretsmanager.model.DeleteSecretRequest;
import software.amazon.awssdk.services.secretsmanager.model.PutSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.ResourceExistsException;
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * AWS implementation of the Cloud Provider Adapter.
 *
 * Uses AWS SDK v2 for:
 * - EC2: VPC, subnets, security groups
 * - ElastiCache: Redis replication groups
 * - Route53: DNS records for endpoint discovery
 * - Secrets Manager: auth tokens
 *
 * Each method is idempotent - safe to call multiple times with same input.
 * Uses resource tags for idempotency checks.
 */
@Component
public class AWSCloudProvider implements CloudProviderAdapter {

    private static final Logger log = LoggerFactory.getLogger(AWSCloudProvider.class);

    private static final String TAG_PLATFORM = "managed-redis-platform";
    private static final String TAG_KEY_INSTANCE_ID = "platform:instance-id";
    private static final String TAG_KEY_TENANT_ID = "platform:tenant-id";

    @Value("${platform.cloud.account-id:}")
    private String accountId;

    @Value("${platform.cloud.region:us-east-1}")
    private String defaultRegion;

    @Value("${platform.cloud.vpc-cidr:10.100.0.0/16}")
    private String vpcCidr;

    @Value("${platform.cloud.route53-hosted-zone-id:}")
    private String hostedZoneId;

    @Value("${platform.cloud.dns-suffix:redis.platform.internal}")
    private String dnsSuffix;

    private Ec2Client ec2Client;
    private ElastiCacheClient elastiCacheClient;
    private Route53Client route53Client;
    private SecretsManagerClient secretsManagerClient;

    @PostConstruct
    public void init() {
        Region region = Region.of(defaultRegion);
        DefaultCredentialsProvider credentialsProvider = DefaultCredentialsProvider.create();

        this.ec2Client = Ec2Client.builder()
                .region(region)
                .credentialsProvider(credentialsProvider)
                .build();

        this.elastiCacheClient = ElastiCacheClient.builder()
                .region(region)
                .credentialsProvider(credentialsProvider)
                .build();

        this.route53Client = Route53Client.builder()
                .region(Region.AWS_GLOBAL)
                .credentialsProvider(credentialsProvider)
                .build();

        this.secretsManagerClient = SecretsManagerClient.builder()
                .region(region)
                .credentialsProvider(credentialsProvider)
                .build();

        log.info("AWS Cloud Provider initialized: region={}, account={}", defaultRegion, accountId);
    }

    @PreDestroy
    public void shutdown() {
        if (ec2Client != null) ec2Client.close();
        if (elastiCacheClient != null) elastiCacheClient.close();
        if (route53Client != null) route53Client.close();
        if (secretsManagerClient != null) secretsManagerClient.close();
    }

    @Override
    public String providerName() {
        return "AWS";
    }

    // ========== Network ==========

    @Override
    public void provisionNetwork(RedisInstance instance) {
        log.info("AWS: Provisioning network for {} in {}", instance.getId(), instance.getRegion());

        // 1. Create or find existing VPC
        String vpcId = findOrCreateVpc(instance);

        // 2. Create subnets across AZs
        List<String> azs = instance.getAvailabilityZonesList();
        if (azs.isEmpty()) {
            azs = List.of(instance.getRegion() + "a", instance.getRegion() + "b", instance.getRegion() + "c");
        }
        List<String> subnetIds = createSubnets(vpcId, azs, instance);

        // 3. Create security group allowing Redis traffic
        String sgId = createSecurityGroup(vpcId, instance);

        // 4. Create ElastiCache subnet group
        createSubnetGroup(instance.getId(), subnetIds);

        log.info("AWS: Network provisioned: vpc={}, subnets={}, sg={}", vpcId, subnetIds.size(), sgId);
    }

    @Override
    public void deleteNetwork(RedisInstance instance) {
        log.info("AWS: Deleting network resources for {}", instance.getId());

        // Delete subnet group
        try {
            elastiCacheClient.deleteCacheSubnetGroup(
                    DeleteCacheSubnetGroupRequest.builder()
                            .cacheSubnetGroupName(subnetGroupName(instance.getId()))
                            .build());
            log.info("AWS: Deleted subnet group for {}", instance.getId());
        } catch (CacheSubnetGroupNotFoundException e) {
            log.debug("AWS: Subnet group already deleted for {}", instance.getId());
        }

        // Delete security group
        String sgId = findSecurityGroupByTag(instance.getId());
        if (sgId != null) {
            ec2Client.deleteSecurityGroup(DeleteSecurityGroupRequest.builder()
                    .groupId(sgId)
                    .build());
            log.info("AWS: Deleted security group {} for {}", sgId, instance.getId());
        }
    }

    // ========== Storage ==========

    @Override
    public void provisionStorage(RedisInstance instance) {
        log.info("AWS: Provisioning storage for {}: persistence={}",
                instance.getId(), instance.isPersistenceEnabled());

        // ElastiCache manages its own storage internally.
        // For persistence, snapshots/backups are configured at deploy time.
        if (instance.isPersistenceEnabled()) {
            log.info("AWS: Persistence enabled - will configure AOF/RDB snapshots on deploy");
        }
    }

    @Override
    public void deleteStorage(RedisInstance instance) {
        log.info("AWS: Cleaning up storage references for {}", instance.getId());

        // Take a final snapshot before deletion
        try {
            elastiCacheClient.createSnapshot(
                    software.amazon.awssdk.services.elasticache.model.CreateSnapshotRequest.builder()
                            .replicationGroupId(replicationGroupId(instance.getId()))
                            .snapshotName("final-" + instance.getId() + "-" + System.currentTimeMillis())
                            .build());
            log.info("AWS: Final snapshot created for {}", instance.getId());
        } catch (Exception e) {
            log.warn("AWS: Could not create final snapshot for {}: {}", instance.getId(), e.getMessage());
        }
    }

    // ========== Redis Deployment ==========

    @Override
    public void deployRedis(RedisInstance instance) {
        log.info("AWS: Deploying Redis replication group: id={}, shards={}, replicas={}",
                instance.getId(), instance.getShards(), instance.getReplicasPerShard());

        String rgId = replicationGroupId(instance.getId());

        // Check if already exists (idempotency)
        if (replicationGroupExists(rgId)) {
            log.info("AWS: Replication group {} already exists, skipping creation", rgId);
            return;
        }

        // Generate auth token and store in Secrets Manager
        String authToken = generateAndStoreAuthToken(instance);

        // Find security group
        String sgId = findSecurityGroupByTag(instance.getId());

        // Create the replication group
        CreateReplicationGroupRequest.Builder requestBuilder = CreateReplicationGroupRequest.builder()
                .replicationGroupId(rgId)
                .replicationGroupDescription("Managed Redis: " + instance.getName() + " (tenant: " + instance.getTenantId() + ")")
                .engine("redis")
                .engineVersion(resolveEngineVersion(instance.getRedisVersion()))
                .cacheNodeType(instance.getInstanceType() != null ? instance.getInstanceType() : "cache.r7g.xlarge")
                .numNodeGroups(instance.getShards())
                .replicasPerNodeGroup(instance.getReplicasPerShard())
                .cacheSubnetGroupName(subnetGroupName(instance.getId()))
                .securityGroupIds(sgId != null ? List.of(sgId) : List.of())
                .automaticFailoverEnabled(instance.getReplicasPerShard() > 0)
                .multiAZEnabled(instance.getAvailabilityZonesList().size() > 1)
                .atRestEncryptionEnabled(instance.isEncryptionAtRest())
                .transitEncryptionEnabled(instance.isTlsEnabled())
                .authToken(authToken)
                .tags(buildElastiCacheTags(instance));

        if (instance.isPersistenceEnabled()) {
            requestBuilder.snapshotRetentionLimit(7);
            requestBuilder.snapshotWindow("03:00-05:00");
        }

        elastiCacheClient.createReplicationGroup(requestBuilder.build());

        log.info("AWS: Replication group {} creation initiated", rgId);
    }

    @Override
    public void deleteRedis(RedisInstance instance) {
        String rgId = replicationGroupId(instance.getId());
        log.info("AWS: Deleting replication group {}", rgId);

        if (!replicationGroupExists(rgId)) {
            log.info("AWS: Replication group {} already deleted", rgId);
            return;
        }

        elastiCacheClient.deleteReplicationGroup(DeleteReplicationGroupRequest.builder()
                .replicationGroupId(rgId)
                .finalSnapshotIdentifier("final-" + rgId + "-" + System.currentTimeMillis())
                .build());

        log.info("AWS: Replication group {} deletion initiated", rgId);

        // Delete auth token from Secrets Manager
        deleteAuthToken(instance);
    }

    // ========== Configuration ==========

    @Override
    public void configureRedis(RedisInstance instance) {
        log.info("AWS: Configuring Redis for {}", instance.getId());

        String rgId = replicationGroupId(instance.getId());

        // Get the endpoint from the replication group
        String endpoint = getReplicationGroupEndpoint(rgId);

        // Create DNS record if Route53 is configured
        if (hostedZoneId != null && !hostedZoneId.isBlank()) {
            String dnsName = instance.getId() + "." + dnsSuffix;
            createDnsRecord(dnsName, endpoint);
            log.info("AWS: DNS record created: {}", dnsName);
        }

        log.info("AWS: Configuration complete for {}. Endpoint: {}", instance.getId(), endpoint);
    }

    // ========== Health Check ==========

    @Override
    public boolean checkHealth(RedisInstance instance) {
        String rgId = replicationGroupId(instance.getId());
        log.debug("AWS: Health check for {}", rgId);

        try {
            DescribeReplicationGroupsResponse response = elastiCacheClient.describeReplicationGroups(
                    DescribeReplicationGroupsRequest.builder()
                            .replicationGroupId(rgId)
                            .build());

            if (response.replicationGroups().isEmpty()) {
                log.warn("AWS: Replication group {} not found", rgId);
                return false;
            }

            ReplicationGroup rg = response.replicationGroups().get(0);
            String status = rg.status();

            if ("available".equals(status)) {
                long healthyNodes = rg.nodeGroups().stream()
                        .flatMap(ng -> ng.nodeGroupMembers().stream())
                        .filter(m -> m.currentRole() != null)
                        .count();

                int expectedNodes = instance.getShards() * (1 + instance.getReplicasPerShard());
                boolean healthy = healthyNodes >= expectedNodes;

                if (!healthy) {
                    log.warn("AWS: Cluster {} has {}/{} healthy nodes", rgId, healthyNodes, expectedNodes);
                }
                return healthy;
            }

            log.warn("AWS: Replication group {} status: {}", rgId, status);
            return false;

        } catch (ReplicationGroupNotFoundException e) {
            log.error("AWS: Replication group {} not found during health check", rgId);
            return false;
        } catch (Exception e) {
            log.error("AWS: Health check failed for {}: {}", rgId, e.getMessage());
            return false;
        }
    }

    // ========== Private Helpers ==========

    private String findOrCreateVpc(RedisInstance instance) {
        DescribeVpcsResponse vpcs = ec2Client.describeVpcs(DescribeVpcsRequest.builder()
                .filters(
                        Filter.builder().name("tag:platform").values(TAG_PLATFORM).build(),
                        Filter.builder().name("tag:region").values(instance.getRegion()).build()
                )
                .build());

        if (!vpcs.vpcs().isEmpty()) {
            String existingVpcId = vpcs.vpcs().get(0).vpcId();
            log.info("AWS: Reusing existing VPC {}", existingVpcId);
            return existingVpcId;
        }

        CreateVpcResponse createVpcResponse = ec2Client.createVpc(CreateVpcRequest.builder()
                .cidrBlock(vpcCidr)
                .tagSpecifications(TagSpecification.builder()
                        .resourceType(ResourceType.VPC)
                        .tags(
                                Tag.builder().key("Name").value("redis-platform-" + instance.getRegion()).build(),
                                Tag.builder().key("platform").value(TAG_PLATFORM).build(),
                                Tag.builder().key("region").value(instance.getRegion()).build()
                        )
                        .build())
                .build());

        String vpcId = createVpcResponse.vpc().vpcId();

        ec2Client.modifyVpcAttribute(ModifyVpcAttributeRequest.builder()
                .vpcId(vpcId)
                .enableDnsHostnames(AttributeBooleanValue.builder().value(true).build())
                .build());

        log.info("AWS: Created VPC {}", vpcId);
        return vpcId;
    }

    private List<String> createSubnets(String vpcId, List<String> azs, RedisInstance instance) {
        DescribeSubnetsResponse existing = ec2Client.describeSubnets(DescribeSubnetsRequest.builder()
                .filters(
                        Filter.builder().name("vpc-id").values(vpcId).build(),
                        Filter.builder().name("tag:" + TAG_KEY_INSTANCE_ID).values(instance.getId()).build()
                )
                .build());

        if (!existing.subnets().isEmpty()) {
            List<String> subnetIds = existing.subnets().stream()
                    .map(Subnet::subnetId)
                    .toList();
            log.info("AWS: Reusing existing subnets: {}", subnetIds);
            return subnetIds;
        }

        List<String> subnetIds = new ArrayList<>();
        int cidrIndex = 1;
        for (String az : azs) {
            String subnetCidr = "10.100." + cidrIndex + ".0/24";
            CreateSubnetResponse subnetResp = ec2Client.createSubnet(CreateSubnetRequest.builder()
                    .vpcId(vpcId)
                    .cidrBlock(subnetCidr)
                    .availabilityZone(az)
                    .tagSpecifications(TagSpecification.builder()
                            .resourceType(ResourceType.SUBNET)
                            .tags(
                                    Tag.builder().key("Name").value("redis-" + instance.getId() + "-" + az).build(),
                                    Tag.builder().key(TAG_KEY_INSTANCE_ID).value(instance.getId()).build(),
                                    Tag.builder().key(TAG_KEY_TENANT_ID).value(instance.getTenantId()).build()
                            )
                            .build())
                    .build());
            subnetIds.add(subnetResp.subnet().subnetId());
            cidrIndex++;
        }

        log.info("AWS: Created {} subnets across AZs: {}", subnetIds.size(), azs);
        return subnetIds;
    }

    private String createSecurityGroup(String vpcId, RedisInstance instance) {
        String existingSg = findSecurityGroupByTag(instance.getId());
        if (existingSg != null) {
            log.info("AWS: Reusing existing security group {}", existingSg);
            return existingSg;
        }

        CreateSecurityGroupResponse sgResp = ec2Client.createSecurityGroup(CreateSecurityGroupRequest.builder()
                .groupName("redis-" + instance.getId())
                .description("Security group for managed Redis " + instance.getId())
                .vpcId(vpcId)
                .tagSpecifications(TagSpecification.builder()
                        .resourceType(ResourceType.SECURITY_GROUP)
                        .tags(
                                Tag.builder().key("Name").value("redis-" + instance.getId()).build(),
                                Tag.builder().key(TAG_KEY_INSTANCE_ID).value(instance.getId()).build(),
                                Tag.builder().key(TAG_KEY_TENANT_ID).value(instance.getTenantId()).build()
                        )
                        .build())
                .build());

        String sgId = sgResp.groupId();

        String ingressCidr = instance.getNetworkAccess() == NetworkAccess.PRIVATE
                ? vpcCidr
                : "0.0.0.0/0";

        ec2Client.authorizeSecurityGroupIngress(AuthorizeSecurityGroupIngressRequest.builder()
                .groupId(sgId)
                .ipPermissions(IpPermission.builder()
                        .ipProtocol("tcp")
                        .fromPort(6379)
                        .toPort(6379)
                        .ipRanges(IpRange.builder().cidrIp(ingressCidr).description("Redis client access").build())
                        .build())
                .build());

        log.info("AWS: Created security group {} with access={}", sgId, instance.getNetworkAccess());
        return sgId;
    }

    private String findSecurityGroupByTag(String instanceId) {
        DescribeSecurityGroupsResponse response = ec2Client.describeSecurityGroups(
                DescribeSecurityGroupsRequest.builder()
                        .filters(Filter.builder()
                                .name("tag:" + TAG_KEY_INSTANCE_ID)
                                .values(instanceId)
                                .build())
                        .build());

        if (!response.securityGroups().isEmpty()) {
            return response.securityGroups().get(0).groupId();
        }
        return null;
    }

    private void createSubnetGroup(String instanceId, List<String> subnetIds) {
        String groupName = subnetGroupName(instanceId);
        try {
            elastiCacheClient.createCacheSubnetGroup(CreateCacheSubnetGroupRequest.builder()
                    .cacheSubnetGroupName(groupName)
                    .cacheSubnetGroupDescription("Subnet group for managed Redis " + instanceId)
                    .subnetIds(subnetIds)
                    .build());
            log.info("AWS: Created subnet group {}", groupName);
        } catch (CacheSubnetGroupAlreadyExistsException e) {
            log.info("AWS: Subnet group {} already exists", groupName);
        }
    }

    private boolean replicationGroupExists(String rgId) {
        try {
            DescribeReplicationGroupsResponse resp = elastiCacheClient.describeReplicationGroups(
                    DescribeReplicationGroupsRequest.builder()
                            .replicationGroupId(rgId)
                            .build());
            return !resp.replicationGroups().isEmpty();
        } catch (ReplicationGroupNotFoundException e) {
            return false;
        }
    }

    private String getReplicationGroupEndpoint(String rgId) {
        try {
            DescribeReplicationGroupsResponse resp = elastiCacheClient.describeReplicationGroups(
                    DescribeReplicationGroupsRequest.builder()
                            .replicationGroupId(rgId)
                            .build());

            if (!resp.replicationGroups().isEmpty()) {
                ReplicationGroup rg = resp.replicationGroups().get(0);
                if (rg.configurationEndpoint() != null) {
                    return rg.configurationEndpoint().address();
                }
                if (!rg.nodeGroups().isEmpty() && rg.nodeGroups().get(0).primaryEndpoint() != null) {
                    return rg.nodeGroups().get(0).primaryEndpoint().address();
                }
            }
        } catch (Exception e) {
            log.warn("AWS: Could not get endpoint for {}: {}", rgId, e.getMessage());
        }
        return rgId + ".cache." + defaultRegion + ".amazonaws.com";
    }

    private String generateAndStoreAuthToken(RedisInstance instance) {
        String authToken = UUID.randomUUID().toString().replace("-", "") +
                UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String secretName = "redis/" + instance.getTenantId() + "/" + instance.getId() + "/auth-token";

        try {
            secretsManagerClient.createSecret(CreateSecretRequest.builder()
                    .name(secretName)
                    .description("Auth token for managed Redis " + instance.getId())
                    .secretString(authToken)
                    .tags(
                            software.amazon.awssdk.services.secretsmanager.model.Tag.builder()
                                    .key(TAG_KEY_INSTANCE_ID).value(instance.getId()).build(),
                            software.amazon.awssdk.services.secretsmanager.model.Tag.builder()
                                    .key(TAG_KEY_TENANT_ID).value(instance.getTenantId()).build()
                    )
                    .build());
            log.info("AWS: Auth token stored in Secrets Manager: {}", secretName);
        } catch (ResourceExistsException e) {
            secretsManagerClient.putSecretValue(PutSecretValueRequest.builder()
                    .secretId(secretName)
                    .secretString(authToken)
                    .build());
            log.info("AWS: Auth token updated in Secrets Manager: {}", secretName);
        }

        return authToken;
    }

    private void deleteAuthToken(RedisInstance instance) {
        String secretName = "redis/" + instance.getTenantId() + "/" + instance.getId() + "/auth-token";
        try {
            secretsManagerClient.deleteSecret(DeleteSecretRequest.builder()
                    .secretId(secretName)
                    .forceDeleteWithoutRecovery(false)
                    .recoveryWindowInDays(7L)
                    .build());
            log.info("AWS: Auth token scheduled for deletion: {}", secretName);
        } catch (ResourceNotFoundException e) {
            log.debug("AWS: Auth token already deleted: {}", secretName);
        }
    }

    private void createDnsRecord(String dnsName, String endpoint) {
        route53Client.changeResourceRecordSets(ChangeResourceRecordSetsRequest.builder()
                .hostedZoneId(hostedZoneId)
                .changeBatch(ChangeBatch.builder()
                        .changes(Change.builder()
                                .action(ChangeAction.UPSERT)
                                .resourceRecordSet(ResourceRecordSet.builder()
                                        .name(dnsName)
                                        .type(RRType.CNAME)
                                        .ttl(60L)
                                        .resourceRecords(ResourceRecord.builder().value(endpoint).build())
                                        .build())
                                .build())
                        .build())
                .build());
    }

    private List<software.amazon.awssdk.services.elasticache.model.Tag> buildElastiCacheTags(RedisInstance instance) {
        return List.of(
                software.amazon.awssdk.services.elasticache.model.Tag.builder()
                        .key(TAG_KEY_INSTANCE_ID).value(instance.getId()).build(),
                software.amazon.awssdk.services.elasticache.model.Tag.builder()
                        .key(TAG_KEY_TENANT_ID).value(instance.getTenantId()).build(),
                software.amazon.awssdk.services.elasticache.model.Tag.builder()
                        .key("platform").value(TAG_PLATFORM).build(),
                software.amazon.awssdk.services.elasticache.model.Tag.builder()
                        .key("Name").value("redis-" + instance.getName()).build()
        );
    }

    private String resolveEngineVersion(String redisVersion) {
        if (redisVersion == null) return "7.1";
        return switch (redisVersion) {
            case "8.x" -> "7.1";
            case "7.x" -> "7.1";
            case "6.x" -> "6.2";
            default -> "7.1";
        };
    }

    private String replicationGroupId(String instanceId) {
        // ElastiCache IDs: max 40 chars, lowercase alphanumeric + hyphens
        return instanceId.length() > 40 ? instanceId.substring(0, 40) : instanceId;
    }

    private String subnetGroupName(String instanceId) {
        return "redis-" + instanceId;
    }
}
