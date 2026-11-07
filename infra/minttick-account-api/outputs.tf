output "account_api_url" {
  value       = local.enabled ? "https://${var.account_domain}" : null
  description = "HTTPS origin once deployed and ACME certificate issued; not a proof of readiness."
}
output "instance_id" {
  value = local.enabled ? aws_instance.account[0].id : null
}
output "ecr_repository_url" {
  value = local.enabled ? aws_ecr_repository.backend[0].repository_url : null
}
output "public_ipv4" {
  value = local.enabled ? aws_eip.account[0].public_ip : null
}
