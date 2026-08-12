output "deployment_name" {
  description = "Name of the Kubernetes Deployment"
  value       = kubernetes_deployment.platform.metadata[0].name
}

output "service_name" {
  description = "Name of the Kubernetes Service"
  value       = kubernetes_service.platform.metadata[0].name
}

output "service_cluster_ip" {
  description = "ClusterIP of the Service"
  value       = kubernetes_service.platform.spec[0].cluster_ip
}

output "namespace" {
  description = "Namespace where resources are deployed"
  value       = var.namespace
}
