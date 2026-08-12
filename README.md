# Managed Redis Platform (Java Spring Boot)

A Kubernetes-native managed Redis-as-a-Service control plane. Customers declare intent, the platform continuously drives infrastructure to match.

## Architecture

```
Customer → Platform API → Policy Engine → State Store → Reconciler → Workflow → Cloud Adapter → K8s Operator → Redis
```

The platform follows the **Kubernetes controller pattern**: observe actual state, compare to desired state, reconcile the difference — continuously.

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Runtime | Java 21, Spring Boot 3.3 |
| API | Spring Web MVC, Bean Validation |
| Persistence | JPA/Hibernate, PostgreSQL (H2 for dev) |
| Cloud | AWS SDK v2 (EC2, ElastiCache, Route53, Secrets Manager) |
| Kubernetes | Fabric8 Kubernetes Client |
| Observability | Micrometer, Prometheus, Logstash JSON |
| Build | Maven, Docker multi-stage |
| Deploy | Terraform, Kubernetes |

## Quick Start

### Prerequisites

- Java 21+
- Maven 3.9+
- Docker (optional, for containerized run)
- AWS credentials configured (for production: IAM role, env vars, or `~/.aws/credentials`)
- Access to a Kubernetes cluster (for production: EKS with kubeconfig or in-cluster service account)

### Run Locally (H2 in-memory DB)

```bash
./mvnw spring-boot:run
```

The API starts on `http://localhost:8080`.

### Create a Redis Instance

```bash
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
```

Response:
```json
{
  "id": "redis-a1b2c3d4",
  "operationId": "op-e5f6g7h8",
  "status": "PROVISIONING"
}
```

### Track the Operation

```bash
curl http://localhost:8080/v1/operations/op-e5f6g7h8
```

### List Instances

```bash
curl -H "X-Tenant-ID: acme-corp" http://localhost:8080/v1/redis-instances
```

### Delete an Instance

```bash
curl -X DELETE -H "X-Tenant-ID: acme-corp" \
  http://localhost:8080/v1/redis-instances/redis-a1b2c3d4
```

## API Reference

| Method | Endpoint | Description | Response |
|--------|----------|-------------|----------|
| `POST` | `/v1/redis-instances` | Create managed Redis | 202 Accepted |
| `GET` | `/v1/redis-instances` | List instances (tenant-scoped) | 200 OK |
| `GET` | `/v1/redis-instances/{id}` | Get instance details | 200 OK |
| `DELETE` | `/v1/redis-instances/{id}` | Request deletion | 202 Accepted |
| `GET` | `/v1/operations/{id}` | Check operation status | 200 OK |
| `GET` | `/actuator/health` | Health check | 200 OK |
| `GET` | `/actuator/prometheus` | Prometheus metrics | 200 OK |

### Request Headers

| Header | Required | Description |
|--------|----------|-------------|
| `X-Tenant-ID` | Yes | Tenant isolation scope |
| `Idempotency-Key` | No | Prevents duplicate creates on retry |
| `X-Request-ID` | No | Correlation ID for distributed tracing |

## How It Works

### 1. Customer declares intent (not infrastructure)

The customer says what they want:
```
100Gi memory, multi-AZ, private network, encrypted
```

They never specify VPC IDs, instance types, shard counts, or storage classes.

### 2. Policy Engine resolves topology

```
100Gi + multi-az → 3 shards, 1 replica/shard, cache.r7g.2xlarge, 3 AZs
```

### 3. State is persisted immediately

```
desired_status = READY
actual_status  = REQUESTED
```

### 4. Reconciliation loop detects drift

Every 30 seconds:
```java
SELECT * FROM redis_instances WHERE desired_status != actual_status
```

### 5. Workflow orchestrator advances one phase per cycle

```
VALIDATING → ALLOCATING → NETWORK_SETUP → STORAGE_SETUP → DEPLOYING → CONFIGURING → HEALTH_CHECK → READY
```

Each phase is persisted. Crash-safe. Idempotent.

### 6. Control plane never stops

After READY, the reconciler + HA controller continuously monitor for drift, degradation, and failures.

## Project Structure

```
src/main/java/io/platform/redis/
├── ManagedRedisPlatformApplication.java
├── api/
│   ├── controller/
│   │   ├── RedisInstanceController.java      # REST API
│   │   ├── OperationController.java          # Operation tracking
│   │   └── GlobalExceptionHandler.java       # Error handling
│   └── dto/                                  # Request/Response DTOs
├── domain/
│   ├── entity/
│   │   ├── RedisInstance.java                # Core entity (desired + actual)
│   │   └── Operation.java                   # Lifecycle operation
│   ├── enums/                               # All state enums
│   └── model/                               # Value objects (Cluster, Topology)
├── repository/
│   ├── RedisInstanceRepository.java          # JPA + findDrifted()
│   └── OperationRepository.java
├── service/
│   ├── PolicyEngine.java                     # Intent → topology
│   ├── PlacementEngine.java                  # Cluster selection
│   ├── WorkflowOrchestrator.java             # Durable state machine
│   ├── ReconciliationController.java         # The control loop
│   ├── HAController.java                     # Failure detection + recovery
│   ├── UpgradeController.java                # Rolling upgrades + rollback
│   ├── CloudProviderAdapter.java             # Interface
│   ├── cloud/
│   │   └── AWSCloudProvider.java             # AWS SDK v2 (EC2, ElastiCache, Route53, SecretsManager)
│   └── KubernetesOperatorService.java        # Fabric8 CRD management + namespace isolation
├── observability/
│   ├── PlatformMetrics.java                  # Prometheus metrics
│   └── CorrelationFilter.java                # Request tracing
└── exception/
    └── PolicyViolationException.java
```

## Design Patterns

| Pattern | Where | Purpose |
|---------|-------|---------|
| Desired State / Reconciliation | `ReconciliationController` | Self-healing, drift correction |
| Durable State Machine | `WorkflowOrchestrator` | Crash-safe provisioning |
| Strategy | `CloudProviderAdapter` interface | Multi-cloud without if/else |
| Filter-Score Pipeline | `PlacementEngine` | Extensible cluster selection |
| Async Operation Tracking | 202 + `/operations/{id}` | Non-blocking provisioning |
| Idempotency | `Idempotency-Key` header | Safe retries |
| Wave-based Rollout | `UpgradeController` | Blast radius reduction |
| Correlation ID | `CorrelationFilter` + MDC | End-to-end tracing |

## Configuration

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `API_PORT` | 8080 | Server port |
| `DB_HOST` | localhost | PostgreSQL host |
| `DB_PORT` | 5432 | PostgreSQL port |
| `DB_NAME` | managed_redis | Database name |
| `DB_USERNAME` | - | DB username (production) |
| `DB_PASSWORD` | - | DB password (production) |
| `PLATFORM_RECONCILER_INTERVAL_SECONDS` | 30 | Reconcile loop interval |
| `PLATFORM_CLOUD_PROVIDER` | aws | Default cloud |
| `PLATFORM_CLOUD_REGION` | us-east-1 | Default region |
| `AWS_ACCOUNT_ID` | - | AWS account ID |
| `AWS_VPC_CIDR` | 10.100.0.0/16 | VPC CIDR block for Redis networks |
| `AWS_ROUTE53_ZONE_ID` | - | Route53 hosted zone for DNS records |
| `AWS_DNS_SUFFIX` | redis.platform.internal | DNS suffix for Redis endpoints |
| `K8S_MASTER_URL` | - | Kubernetes API server URL (auto-detected if in-cluster) |
| `K8S_NAMESPACE` | default | Default Kubernetes namespace |
| `KUBECONFIG_PATH` | - | Path to kubeconfig file (for out-of-cluster) |
| `SPRING_PROFILES_ACTIVE` | default | `production` for JSON logs + PostgreSQL |

## Build & Deploy

### Build JAR

```bash
./mvnw clean package -DskipTests
```

### Build Docker Image

```bash
docker build -t managed-redis-platform:1.0.0 .
```

### Run with Docker

```bash
docker run -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=production \
  -e DB_HOST=your-db-host \
  -e DB_USERNAME=admin \
  -e DB_PASSWORD=secret \
  managed-redis-platform:1.0.0
```

### Deploy to Kubernetes (Terraform)

```bash
cd deploy/terraform
cp terraform.tfvars.example terraform.tfvars
# Edit terraform.tfvars with your environment values

terraform init
terraform plan
terraform apply
```

## Observability

### Metrics (Prometheus)

```
redis_create_requests_total
redis_create_successes_total
redis_create_failures_total
redis_create_duration_seconds{quantile="0.99"}
redis_upgrade_total
redis_rollback_total
redis_reconcile_cycles_total
redis_reconcile_errors_total
redis_instances_degraded
```

### Structured Logs (Production)

```json
{
  "timestamp": "2026-08-12T09:15:30.123Z",
  "level": "INFO",
  "logger": "io.platform.redis.service.WorkflowOrchestrator",
  "message": "Phase transition: NETWORK_SETUP → STORAGE_SETUP",
  "tenantId": "acme-corp",
  "operationId": "op-e5f6g7h8",
  "resourceId": "redis-a1b2c3d4",
  "requestId": "trace-abc123",
  "service": "managed-redis-platform"
}
```

### Health Endpoints

- `/actuator/health` — liveness + readiness
- `/actuator/prometheus` — Prometheus scrape endpoint
- `/actuator/info` — application info

## Testing

```bash
# Run all tests
./mvnw test

# Run with coverage
./mvnw test jacoco:report
```

## Production Checklist

- [ ] Replace H2 with PostgreSQL (set `SPRING_PROFILES_ACTIVE=production`)
- [ ] Add Flyway migrations for schema management
- [ ] Configure API Gateway (auth, rate limiting, TLS)
- [ ] Set up Secrets Manager for DB credentials and Redis auth
- [ ] Deploy with `replicas >= 3` and PodDisruptionBudget
- [ ] Configure Prometheus + Grafana dashboards
- [ ] Set up alerts for SLI breaches (provisioning rate, latency)
- [x] Wire real AWS SDK calls in `AWSCloudProvider` (EC2, ElastiCache, Route53, Secrets Manager)
- [x] Connect Fabric8 client to target EKS clusters (CRD creation, namespace isolation)
- [ ] Configure `AWS_ACCOUNT_ID`, `AWS_ROUTE53_ZONE_ID`, and AWS credentials
- [ ] Set up `KUBECONFIG_PATH` or deploy in-cluster with proper ServiceAccount RBAC
- [ ] Set up CI/CD pipeline (build → test → push → terraform apply)

## License

Internal platform — not for redistribution.
