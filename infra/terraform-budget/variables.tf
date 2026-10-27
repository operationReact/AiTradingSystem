variable "aws_region" {
  description = "AWS region. Mumbai is ap-south-1."
  type = string
  default = "ap-south-1"
}
variable "domain_name" {
  description = "Domain managed by the operator. Delegate Route 53 nameservers after apply."
  type = string
  default = "maargglobal.com"
}
variable "allowed_github_repository" {
  type = string
  default = "operationReact/AiTradingSystem"
}
variable "allowed_github_branch" {
  type = string
  default = "dev"
}
variable "budget_alert_email" {
  description = "Monitored email for AWS Budget actual and forecast alerts."
  type = string
  validation {
    condition = can(regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", var.budget_alert_email))
    error_message = "Set budget_alert_email to a valid email address you monitor."
  }
}
variable "monthly_budget_usd" {
  type = number
  default = 25
  validation {
    condition = var.monthly_budget_usd > 0 && var.monthly_budget_usd <= 25
    error_message = "This stack is intentionally capped at USD 25/month."
  }
}
