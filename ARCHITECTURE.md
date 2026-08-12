# Managed Redis Platform — Architecture & Design Document

## 1. Executive Summary

This platform provides managed Redis-as-a-Service built on Kubernetes. Customers declare
**intent** (what they want), and the platform continuously drives infrastructure to match
that intent. The system follows the Kubernetes controller pattern at the platform level:
observe actual state, compare to desired state, reconcile the difference.

**Technology Stack:** Java 21, Spring Boot 3.3, JPA/Hibernate, AWS SDK v2 (EC2, ElastiCache, Route53, Secrets Manager), Fabric8 Kubernetes Client, Micrometer

---

## 2. Design Principles

| Principle | Implementation |
|-----------|---------------|
| **Intent-driven** | Customer declares `100Gi, multi-az, private` — never VPC IDs or instance types |
| **Asynchronous** | All provisioning returns `202 Accepted` with an operation ID |
| **Idempotent** | `Idempotency-Key` header prevents duplicate creates |
| **Resumable** | Each workflow phase is persisted; crash → resume from last phase |
| **Observable** | Every log line carries tenantId, operationId, resourceId |
| **Multi-cloud** | Interface-based cloud adapter; AWS today, GCP/Azure tomorrow |
| **Eventually consistent** | Reconciliation loop continuously corrects drift |

---

## 3. Architecture Diagram

```
                           Customer
                               │
                               ▼
                        ┌─────────────┐
                        │ API Gateway │  auth, rate-limit, TLS termination
                        └──────┬──────┘
                               │
                               ▼
                    ┌───────────────────────┐
                    │     Platform API      │  Spring Boot REST
                    │  /v1/redis-instances  │  validation, idempotency
                    └───────────┬───────────┘
                                │
              ┌─────────────────┼─────────────────┐
              │                 │                  │
              ▼                 ▼                  ▼
     ┌────────────────┐  ┌───────────┐   ┌──────────────┐
     │  State Store   │  │ Operation │   │   Policy     │
     │ (PostgreSQL)   │  │   Store   │   │   Engine     │
     │                │  │           │   │              │
     │ desired_status │  │ phase     │   │ intent →     │
     │ actual_status  │  │ status    │   │  topology    │
     └───────┬────────┘  └───────────┘   └──────────────┘
             │
             ▼
     ┌────────────────────────────────┐
     │   Reconciliation Controller    │  @Scheduled(30s)
     │                                │
     │   if (desired != actual) →     │
     │       reconcile()              │
     └───────────────┬────────────────┘
                     │
         ┌───────────┼───────────────┐
         │           │               │
         ▼           ▼               ▼
  ┌──────────┐ ┌───────────┐ ┌──────────────┐
  │Placement │ │ Workflow   │ │     HA       │
  │ Engine   │ │Orchestrator│ │ Controller   │
  └──────────┘ └─────┬─────┘ └──────────────┘
                     │
            ┌────────┼────────┐
            │                 │
            ▼                 ▼
    ┌──────────────┐   ┌───────────────┐
    │Cloud Provider│   │  Kubernetes   │
    │  Adapter     │   │   Operator    │
    │  (AWS impl)  │   │  Integration  │
    └──────┬───────┘   └───────┬───────┘
           │                   │
           ▼                   ▼
    ┌──────────────┐   ┌───────────────┐
    │  AWS APIs    │   │  EKS Cluster  │
    │ EC2 (VPC/SG) │   │              │
    │ ElastiCache  │   │ ManagedRedis │
    │ Route53 (DNS)│   │   Operator   │
    │ SecretsManager│  │      │        │
    └──────────────┘   │  StatefulSets │
                       │  Services     │
                       │  PVCs/PDBs    │
                       └───────────────┘
```

---

## 4. Component Deep-Dive

### 4.1 Platform API

**Package:** `io.platform.redis.api`

The API is the customer-facing contract. It exposes the **platform model**, never cloud internals.

**Endpoints:**
```
POST   /v1/redis-instances         → 202 Accepted + operationId
GET    /v1/redis-instances/{id}    → instance state + endpoint
GET    /v1/redis-instances         → list (tenant-scoped)
DELETE /v1/redis-instances/{id}    → 202 Accepted + operationId
GET    /v1/operations/{id}         → operation status + phase
```

**Design decisions:**
- All mutations are **asynchronous** (202 Accepted) because provisioning takes minutes
- **Idempotency-Key** header prevents duplicate creates on network retry
- **X-Tenant-ID** header scopes all queries (multi-tenant isolation)
- Request validation via Bean Validation (`@NotBlank`, `@Pattern`)

### 4.2 Desired State Model

**Package:** `io.platform.redis.domain.entity`

The core insight: track **two separate states** for every resource.

```java
@Entity
public class RedisInstance {
    private ResourceStatus desiredStatus;  // what customer wants
    private ResourceStatus actualStatus;   // what actually exists
}
```

The **difference** between these two values is what drives the entire control plane.

```sql
-- The reconciler's core query:
SELECT * FROM redis_instances WHERE desired_status != actual_status;
```

### 4.3 Policy Engine

**Package:** `io.platform.redis.service.PolicyEngine`

Maps customer **intent** into concrete **topology**:

```
Input: 100Gi memory + multi-az + AWS us-east-1
                    │
                    ▼
             Policy Engine
                    │
Output:             ▼
  shards = 3
  replicas/shard = 1
  instanceType = cache.r7g.2xlarge
  storageClass = gp3-encrypted
  storageSize = 66Gi per shard
  AZs = [us-east-1a, us-east-1b, us-east-1c]
```

**Policy rules enforced:**
- Memory bounds (1Gi–1024Gi)
- Supported Redis versions (7.x, 8.x)
- Multi-AZ requires persistence (data safety)
- Public access requires TLS (security)

### 4.4 Placement Engine

**Package:** `io.platform.redis.service.PlacementEngine`

Selects the optimal target cluster using a filter → score pipeline:

```
All clusters
    │
    ├── Filter: wrong cloud         → removed
    ├── Filter: wrong region        → removed
    ├── Filter: unhealthy           → removed
    ├── Filter: at capacity         → removed
    ├── Filter: insufficient AZs   → removed
    │
    ▼
Eligible clusters
    │
    ├── Score: capacity headroom    (40 points)
    ├── Score: AZ count             (30 points)
    ├── Score: tenant isolation     (30 points)
    │
    ▼
Select highest score
```

### 4.5 Workflow Orchestrator

**Package:** `io.platform.redis.service.WorkflowOrchestrator`

Provisioning as a **durable state machine** — NOT a giant function:

```
VALIDATING → ALLOCATING → NETWORK_SETUP → STORAGE_SETUP → DEPLOYING → CONFIGURING → HEALTH_CHECK → READY
```

**Why not a single function?**

If `createRedis()` crashes after network setup, you'd have to:
- Know what was already done
- Avoid creating duplicates
- Figure out where to restart

With a state machine:
- Each phase is **persisted** (`Operation.phase = STORAGE_SETUP`)
- On restart: read phase, check if step completed, continue
- Each phase handler is **idempotent** — safe to re-execute

```java
// The reconciler calls this repeatedly until READY
public void advanceOperation(Operation op) {
    WorkflowPhase nextPhase = executePhase(op.getPhase(), instance, op);
    op.advancePhase(nextPhase);
    // ... persist
}
```

### 4.6 Cloud Provider Adapter

**Package:** `io.platform.redis.service.cloud`

Interface-based design — express WHAT you need, not HOW:

```java
public interface CloudProviderAdapter {
    void provisionNetwork(RedisInstance instance);   // AWS → VPC + subnets + SG
    void provisionStorage(RedisInstance instance);   // AWS → ElastiCache snapshots
    void deployRedis(RedisInstance instance);        // AWS → ElastiCache replication group
    void configureRedis(RedisInstance instance);     // AWS → Route53 DNS + endpoint
    boolean checkHealth(RedisInstance instance);     // AWS → replication group status
}
```

**AWS Implementation (`AWSCloudProvider`)** uses AWS SDK v2:

| Method | AWS Services | What It Does |
|--------|-------------|--------------|
| `provisionNetwork` | EC2 | Create/reuse VPC, subnets across AZs, security group (port 6379), ElastiCache subnet group |
| `provisionStorage` | ElastiCache | Configure snapshot retention (ElastiCache manages storage internally) |
| `deployRedis` | ElastiCache, Secrets Manager | Create replication group with sharding, multi-AZ, encryption, auth token |
| `configureRedis` | Route53 | Read endpoint from replication group, create CNAME DNS record |
| `checkHealth` | ElastiCache | Query replication group status, verify node counts |
| `deleteRedis` | ElastiCache, Secrets Manager | Delete replication group (with final snapshot), remove auth token |
| `deleteNetwork` | EC2, ElastiCache | Delete subnet group, security group |

All methods are **idempotent** — they check for existing resources via tags before creating.

Adding GCP support means implementing a `GCPCloudProvider` — no changes to core logic.

### 4.7 Kubernetes Operator Service

**Package:** `io.platform.redis.service.KubernetesOperatorService`

Manages ManagedRedis CRDs on target EKS clusters via Fabric8 Kubernetes Client:

```
Control Plane                              Target EKS Cluster
     │                                          │
     ├── ensureNamespace("tenant-acme-corp") ──→│ Creates namespace + NetworkPolicy
     │                                          │
     ├── createManagedRedis(instance) ─────────→│ Creates ManagedRedis CRD
     │                                          │   ├── spec.shards: 3
     │                                          │   ├── spec.replicasPerShard: 1
     │                                          │   ├── spec.security.tls: true
     │                                          │   └── spec.persistence.enabled: true
     │                                          │
     ├── getStatus(instance) ←─────────────────│ Reads .status from CRD
     │                                          │   ├── phase: "Ready"
     │                                          │   ├── readyShards: 3
     │                                          │   └── endpoint: "redis-xxx..."
     │                                          │
     └── deleteManagedRedis(instance) ─────────→│ Deletes CRD (operator cleans up)
```

**Key features:**
- **Tenant isolation:** Each tenant gets a dedicated namespace with default-deny NetworkPolicy
- **Idempotent operations:** Checks for existing resources before creating
- **Flexible auth:** Supports kubeconfig file, in-cluster ServiceAccount, or explicit master URL
- **CRD-based:** Uses `GenericKubernetesResource` for the `platform.redis.io/v1 ManagedRedis` CRD

### 4.8 Reconciliation Controller

**Package:** `io.platform.redis.service.ReconciliationController`

The Kubernetes controller pattern at platform scale:

```java
@Scheduled(fixedDelay = 30000)
public void reconcile() {
    List<RedisInstance> drifted = repository.findDrifted();
    for (RedisInstance instance : drifted) {
        reconcileInstance(instance);
    }
}
```

This handles:
- New instances (desired=READY, actual=REQUESTED) → provision
- Deletions (desired=DELETED, actual=READY) → teardown
- Degraded instances → heal
- Failed instances → evaluate retry

**The reconciler never stops.** Even after provisioning completes, it continuously monitors for drift.

### 4.9 HA Controller

**Package:** `io.platform.redis.service.HAController`

Failure detection with automated response matrix:

| Failure | Detection | Response | RTO | Automatic |
|---------|-----------|----------|-----|-----------|
| Process crash | Liveness probe | Restart container | 30s | ✓ |
| Pod failure | Health check | K8s reschedule | 60s | ✓ |
| Node failure | Node NotReady | Reschedule pod | 2min | ✓ |
| Primary loss | Replication monitor | Promote replica | 30s | ✓ |
| AZ failure | Multi-AZ health | Failover to surviving AZ | 2min | ✓ |
| Storage I/O | Error monitoring | Alert + manual replace | 10min | ✗ |

### 4.10 Upgrade Controller

**Package:** `io.platform.redis.service.UpgradeController`

Zero-downtime upgrades via rolling strategy:

```
PRECHECK → CAPACITY → BACKUP → UPGRADE_REPLICA → HEALTH → FAILOVER → UPGRADE_PRIMARY → VALIDATE → COMPLETE
```

**Rollback boundary:** The backup/snapshot phase defines the point of no return.
- Before: automatic rollback (restore previous binary)
- After: restore from snapshot required

**Wave-based rollout** (blast radius reduction):
```
Wave 0: Internal (0%)    → 1 hour bake
Wave 1: Canary (1%)      → 4 hours bake
Wave 2: Early (10%)      → 24 hours bake
Wave 3: Majority (50%)   → 24 hours bake
Wave 4: Complete (100%)
```

---

## 5. Patterns Used

### 5.1 Desired State / Reconciliation Pattern
**Origin:** Kubernetes controllers
**Implementation:** `RedisInstance.desiredStatus` vs `actualStatus` + `ReconciliationController`
**Benefit:** Self-healing; declare what you want, system converges

### 5.2 Durable State Machine Pattern
**Origin:** Workflow engines (Temporal, Step Functions)
**Implementation:** `WorkflowOrchestrator` with persisted `Operation.phase`
**Benefit:** Crash-safe provisioning; resume from any point

### 5.3 Strategy Pattern (Cloud Adapter)
**Origin:** Gang of Four
**Implementation:** `CloudProviderAdapter` interface + `AWSCloudProvider`
**Benefit:** Multi-cloud without if/else chains; add providers via new implementations

### 5.4 Filter-Score Pipeline (Placement)
**Origin:** Kubernetes scheduler
**Implementation:** `PlacementEngine.filter()` → `PlacementEngine.score()`
**Benefit:** Extensible cluster selection; add new criteria without restructuring

### 5.5 Intent-Based API Pattern
**Origin:** Declarative infrastructure (Terraform, K8s)
**Implementation:** `CreateRedisRequest` exposes "what" not "how"
**Benefit:** Customers never couple to infrastructure details

### 5.6 Async Operation Tracking Pattern
**Origin:** AWS/GCP APIs
**Implementation:** 202 Accepted + `GET /operations/{id}` for polling
**Benefit:** Client isn't blocked; server can retry internally

### 5.7 Idempotency Pattern
**Origin:** Stripe API design
**Implementation:** `Idempotency-Key` header → duplicate detection
**Benefit:** Network retries never create duplicate resources

### 5.8 Wave-Based Rollout Pattern
**Origin:** Google SRE (canarying)
**Implementation:** `UpgradeController.DEFAULT_WAVES`
**Benefit:** Limits blast radius; catch issues before they hit 100%

### 5.9 Observer/Correlation Pattern
**Origin:** Distributed tracing
**Implementation:** `CorrelationFilter` → MDC → structured JSON logs
**Benefit:** Trace any request through API → workflow → cloud → operator

---

## 6. Data Flow: End-to-End Create

```
1. Customer → POST /v1/redis-instances
   { name: "payments-cache", memory: "100Gi", availability: "multi-az" }

2. API validates request (Bean Validation)

3. API checks Idempotency-Key → no duplicate

4. Policy Engine resolves topology:
   100Gi + multi-az → 3 shards, 1 replica/shard, r7g.2xlarge

5. Desired State persisted to DB:
   desiredStatus=READY, actualStatus=REQUESTED

6. Operation created: op-xxxx, status=PENDING, phase=VALIDATING

7. Response: 202 Accepted { operationId: "op-xxxx" }

8. Reconciliation Controller (30s loop):
   → finds drifted instance (READY ≠ REQUESTED)
   → calls WorkflowOrchestrator.advanceOperation()

9. Orchestrator executes phases one per cycle:
   VALIDATING → ALLOCATING (placement) → NETWORK_SETUP (AWS VPC)
   → STORAGE_SETUP (EBS) → DEPLOYING (K8s CRD) → CONFIGURING (TLS/DNS)
   → HEALTH_CHECK → READY

10. Instance reaches READY state:
    - endpoint = redis-xxxx.redis.platform.internal
    - actualStatus = READY
    - desiredStatus = READY
    - reconciler: no drift → no action

11. Control plane continues monitoring for drift
```

---

## 7. Project Structure

```
managed-redis-platform/
├── pom.xml                                    # Spring Boot 3.3, Java 21, AWS SDK v2, Fabric8
├── Dockerfile                                 # Multi-stage (temurin:21)
├── src/main/java/io/platform/redis/
│   ├── ManagedRedisPlatformApplication.java   # Entry point
│   ├── api/
│   │   ├── controller/
│   │   │   ├── RedisInstanceController.java   # POST/GET/DELETE
│   │   │   ├── OperationController.java       # GET operation status
│   │   │   └── GlobalExceptionHandler.java    # Error responses
│   │   └── dto/
│   │       ├── CreateRedisRequest.java        # Validated input
│   │       ├── CreateRedisResponse.java
│   │       ├── GetRedisResponse.java
│   │       └── GetOperationResponse.java
│   ├── domain/
│   │   ├── entity/
│   │   │   ├── RedisInstance.java             # Core domain entity
│   │   │   └── Operation.java                # Lifecycle operation
│   │   ├── enums/                            # All state enums
│   │   └── model/
│   │       ├── Cluster.java                  # Placement target
│   │       ├── PlacementResult.java
│   │       └── ResolvedTopology.java         # Policy output
│   ├── repository/
│   │   ├── RedisInstanceRepository.java      # JPA + findDrifted()
│   │   └── OperationRepository.java
│   ├── service/
│   │   ├── PolicyEngine.java                 # Intent → topology
│   │   ├── PlacementEngine.java              # Filter → score → select
│   │   ├── WorkflowOrchestrator.java         # Durable state machine
│   │   ├── ReconciliationController.java     # The control loop
│   │   ├── CloudProviderAdapter.java         # Interface
│   │   ├── cloud/
│   │   │   └── AWSCloudProvider.java         # AWS SDK v2 (EC2, ElastiCache, Route53, SecretsManager)
│   │   ├── KubernetesOperatorService.java    # Fabric8: CRD lifecycle + namespace isolation
│   │   ├── HAController.java                 # Failure detection
│   │   └── UpgradeController.java            # Rolling upgrades
│   ├── observability/
│   │   ├── PlatformMetrics.java              # Prometheus counters/timers
│   │   └── CorrelationFilter.java            # Request tracing
│   └── exception/
│       └── PolicyViolationException.java
├── src/main/resources/
│   ├── application.yml                       # Config (H2 dev, PG prod, AWS, K8s)
│   └── logback-spring.xml                    # Structured JSON logging
└── deploy/
    └── terraform/
        ├── main.tf                           # Deployment, Service, ServiceAccount, PDB
        ├── variables.tf                      # All configurable inputs
        ├── outputs.tf                        # Deployment/Service names, ClusterIP
        └── terraform.tfvars.example          # Example configuration
```

---

## 8. Standards Enforced

### Provisioning Standard
- All lifecycle operations: async, idempotent, resumable, auditable
- Operations cancellable when safe
- Timeout with auto-failure after SLO breach

### HA Standard
- Production instances span ≥2 failure domains (AZs)
- Automated failover for process, pod, primary, AZ failures
- Defined RTO/RPO per failure class
- PodDisruptionBudgets prevent voluntary disruption

### Deployment Standard
- Health gates between phases (no READY without health check)
- Wave-based rollout limits concurrency
- Previous version preserved until validation passes
- Rollback boundary defined before each upgrade

### Observability Standard
- Structured JSON logs (logstash encoder)
- Prometheus metrics with percentiles (P50, P95, P99)
- Every log includes: tenantId, resourceId, operationId, requestId
- SLIs defined: provisioning success rate, creation latency, endpoint availability

---

## 9. Running the Platform

```bash
# Development (H2 in-memory DB, no AWS/K8s needed)
mvn spring-boot:run

# Build
mvn clean package -DskipTests

# Docker
docker build -t managed-redis-platform:1.0.0 .
docker run -p 8080:8080 managed-redis-platform:1.0.0

# Kubernetes (Terraform)
cd deploy/terraform
cp terraform.tfvars.example terraform.tfvars
# Edit terraform.tfvars with your environment values
terraform init
terraform plan
terraform apply

# Test the API
curl -X POST http://localhost:8080/v1/redis-instances \
  -H "Content-Type: application/json" \
  -H "X-Tenant-ID: acme-corp" \
  -H "Idempotency-Key: req-001" \
  -d '{
    "name": "payments-cache",
    "cloud": "aws",
    "region": "us-east-1",
    "memory": "100Gi",
    "availability": "multi-az",
    "persistence": true,
    "networkAccess": "private",
    "encryptionAtRest": true,
    "tls": true,
    "redisVersion": "8.x"
  }'

# Check operation
curl http://localhost:8080/v1/operations/op-xxxxxxxx

# Check instance
curl -H "X-Tenant-ID: acme-corp" http://localhost:8080/v1/redis-instances
```

### Environment Variables for Production

```bash
# AWS (required for real provisioning)
export AWS_ACCOUNT_ID=123456789012
export AWS_VPC_CIDR=10.100.0.0/16
export AWS_ROUTE53_ZONE_ID=Z1234567890ABC
export AWS_DNS_SUFFIX=redis.platform.internal
# AWS credentials via env vars, IAM role, or ~/.aws/credentials

# Kubernetes (auto-detected if running in-cluster)
export K8S_MASTER_URL=https://eks-cluster.us-east-1.eks.amazonaws.com
export KUBECONFIG_PATH=/path/to/kubeconfig

# Database
export SPRING_PROFILES_ACTIVE=production
export DB_HOST=postgres.example.com
export DB_USERNAME=platform
export DB_PASSWORD=secret
```

---

## 10. Production Considerations

| Area | Approach |
|------|----------|
| **Database** | Replace H2 with PostgreSQL (Flyway migrations) |
| **Authentication** | JWT via API Gateway or Spring Security OAuth2 |
| **Multi-tenancy** | Row-level filtering via `tenantId` on all queries |
| **Rate limiting** | API Gateway or Spring Cloud Gateway |
| **Secrets** | AWS Secrets Manager (auth tokens auto-managed by platform) |
| **Cloud infra** | AWS SDK v2: EC2 (VPC/SG), ElastiCache (Redis), Route53 (DNS), Secrets Manager |
| **K8s operator** | Fabric8 client creates ManagedRedis CRDs; operator on EKS reconciles to StatefulSets |
| **Tenant isolation** | Separate K8s namespaces + NetworkPolicy per tenant (auto-created) |
| **CI/CD** | GitHub Actions → build → test → push image → `terraform apply` |
| **Deploy** | Terraform (deploy/terraform/) manages K8s Deployment, Service, PDB |
| **Scaling** | HPA on CPU; control plane is stateless (DB is the state) |
| **DR** | Multi-region PostgreSQL with read replicas; active-passive failover |
