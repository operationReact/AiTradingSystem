output "paper_instance_id" {
  description = "Operator starts this EC2 instance only when needed; auto-stop at 22:00 IST."
  value       = local.enabled ? aws_instance.paper[0].id : null
}
output "instance_profile" {
  value = local.enabled ? aws_iam_instance_profile.paper[0].name : null
}
output "paper_auto_stop_name" {
  value = local.enabled ? aws_scheduler_schedule.daily_stop[0].name : null
}
output "public_access" {
  value = "No inbound ports; use AWS Systems Manager Session Manager after user-data and SSM are healthy."
}
