variable "kubeconfig_path" {
  description = "Path to kubeconfig file"
  type        = string
  default     = "~/.kube/config"
}

variable "kubeconfig_context" {
  description = "Kubernetes context to use"
  type        = string
  default     = null
}

variable "namespace" {
  description = "Kubernetes namespace to deploy into"
  type        = string
  default     = "default"
}

variable "release_name" {
  description = "Name for the deployment (equivalent to Helm release name)"
  type        = string
  default     = "redis-platform"
}

# --- Image ---

variable "image_repository" {
  description = "Container image repository"
  type        = string
  default     = "managed-redis-platform"
}

variable "image_tag" {
  description = "Container image tag"
  type        = string
  default     = "1.0.0"
}

variable "image_pull_policy" {
  description = "Image pull policy"
  type        = string
  default     = "IfNotPresent"
}

# --- Deployment ---

variable "replica_count" {
  description = "Number of pod replicas"
  type        = number
  default     = 3
}

variable "spring_profiles" {
  description = "Spring Boot active profiles"
  type        = string
  default     = "production"
}

# --- Database ---

variable "database_host" {
  description = "PostgreSQL host"
  type        = string
  default     = "postgresql"
}

variable "database_port" {
  description = "PostgreSQL port"
  type        = number
  default     = 5432
}

variable "database_name" {
  description = "PostgreSQL database name"
  type        = string
  default     = "managed_redis"
}

variable "database_existing_secret" {
  description = "Name of existing Kubernetes secret with DB credentials"
  type        = string
  default     = "db-credentials"
}

variable "database_secret_key" {
  description = "Key in the secret that contains the DB password"
  type        = string
  default     = "password"
}

# --- Platform ---

variable "reconciler_interval_seconds" {
  description = "Reconciliation loop interval in seconds"
  type        = number
  default     = 30
}

variable "cloud_provider" {
  description = "Default cloud provider"
  type        = string
  default     = "aws"
}

variable "cloud_region" {
  description = "Default cloud region"
  type        = string
  default     = "us-east-1"
}

# --- Service ---

variable "service_type" {
  description = "Kubernetes Service type"
  type        = string
  default     = "ClusterIP"
}

variable "service_port" {
  description = "Service port"
  type        = number
  default     = 8080
}

# --- Resources ---

variable "resources_requests_cpu" {
  description = "CPU request"
  type        = string
  default     = "500m"
}

variable "resources_requests_memory" {
  description = "Memory request"
  type        = string
  default     = "1Gi"
}

variable "resources_limits_cpu" {
  description = "CPU limit"
  type        = string
  default     = "2"
}

variable "resources_limits_memory" {
  description = "Memory limit"
  type        = string
  default     = "2Gi"
}

# --- PodDisruptionBudget ---

variable "pdb_min_available" {
  description = "Minimum available pods in PDB"
  type        = string
  default     = "2"
}
