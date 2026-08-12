terraform {
  required_version = ">= 1.5.0"

  required_providers {
    kubernetes = {
      source  = "hashicorp/kubernetes"
      version = "~> 2.31"
    }
  }
}

provider "kubernetes" {
  config_path    = var.kubeconfig_path
  config_context = var.kubeconfig_context
}

locals {
  app_name  = "managed-redis-platform"
  instance  = var.release_name
  component = "control-plane"

  common_labels = {
    "app.kubernetes.io/name"      = local.app_name
    "app.kubernetes.io/instance"  = local.instance
    "app.kubernetes.io/component" = local.component
    "app.kubernetes.io/managed-by" = "terraform"
  }

  selector_labels = {
    "app.kubernetes.io/name"     = local.app_name
    "app.kubernetes.io/instance" = local.instance
  }
}

# --- ServiceAccount ---
resource "kubernetes_service_account" "platform" {
  metadata {
    name      = local.instance
    namespace = var.namespace
    labels    = local.common_labels
  }
}

# --- Deployment ---
resource "kubernetes_deployment" "platform" {
  metadata {
    name      = local.instance
    namespace = var.namespace
    labels    = local.common_labels
  }

  spec {
    replicas = var.replica_count

    selector {
      match_labels = local.selector_labels
    }

    strategy {
      type = "RollingUpdate"
      rolling_update {
        max_unavailable = "1"
        max_surge       = "1"
      }
    }

    template {
      metadata {
        labels = merge(local.common_labels, local.selector_labels)
        annotations = {
          "prometheus.io/scrape" = "true"
          "prometheus.io/port"   = "8080"
          "prometheus.io/path"   = "/actuator/prometheus"
        }
      }

      spec {
        service_account_name = kubernetes_service_account.platform.metadata[0].name

        container {
          name              = "platform"
          image             = "${var.image_repository}:${var.image_tag}"
          image_pull_policy = var.image_pull_policy

          port {
            name           = "http"
            container_port = 8080
            protocol       = "TCP"
          }

          env {
            name  = "SPRING_PROFILES_ACTIVE"
            value = var.spring_profiles
          }

          env {
            name  = "DB_HOST"
            value = var.database_host
          }

          env {
            name  = "DB_PORT"
            value = tostring(var.database_port)
          }

          env {
            name  = "DB_NAME"
            value = var.database_name
          }

          env {
            name = "DB_USERNAME"
            value_from {
              secret_key_ref {
                name = var.database_existing_secret
                key  = "username"
              }
            }
          }

          env {
            name = "DB_PASSWORD"
            value_from {
              secret_key_ref {
                name = var.database_existing_secret
                key  = var.database_secret_key
              }
            }
          }

          env {
            name  = "PLATFORM_RECONCILER_INTERVAL_SECONDS"
            value = tostring(var.reconciler_interval_seconds)
          }

          env {
            name  = "PLATFORM_CLOUD_PROVIDER"
            value = var.cloud_provider
          }

          env {
            name  = "PLATFORM_CLOUD_REGION"
            value = var.cloud_region
          }

          liveness_probe {
            http_get {
              path = "/actuator/health/liveness"
              port = "http"
            }
            initial_delay_seconds = 30
            period_seconds        = 10
            failure_threshold     = 3
          }

          readiness_probe {
            http_get {
              path = "/actuator/health/readiness"
              port = "http"
            }
            initial_delay_seconds = 15
            period_seconds        = 5
          }

          resources {
            requests = {
              cpu    = var.resources_requests_cpu
              memory = var.resources_requests_memory
            }
            limits = {
              cpu    = var.resources_limits_cpu
              memory = var.resources_limits_memory
            }
          }
        }

        topology_spread_constraint {
          max_skew           = 1
          topology_key       = "topology.kubernetes.io/zone"
          when_unsatisfiable = "DoNotSchedule"

          label_selector {
            match_labels = local.selector_labels
          }
        }
      }
    }
  }
}

# --- Service ---
resource "kubernetes_service" "platform" {
  metadata {
    name      = local.instance
    namespace = var.namespace
    labels    = local.common_labels
  }

  spec {
    type = var.service_type

    port {
      name        = "http"
      port        = var.service_port
      target_port = "http"
      protocol    = "TCP"
    }

    selector = local.selector_labels
  }
}

# --- PodDisruptionBudget ---
resource "kubernetes_pod_disruption_budget" "platform" {
  metadata {
    name      = local.instance
    namespace = var.namespace
    labels    = local.common_labels
  }

  spec {
    min_available = var.pdb_min_available

    selector {
      match_labels = local.selector_labels
    }
  }
}
