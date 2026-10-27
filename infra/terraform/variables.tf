variable "aws_region" {
  description = "AWS region. Hyderabad is ap-south-2."
  type        = string
  default     = "ap-south-2"
}
variable "domain_name" {
  description = "Domain you control. A Route 53 hosted zone is created; delegate its nameservers at your registrar."
  type        = string
  default     = "maargglobal.com"
}
variable "app_subdomain" {
  description = "Optional app subdomain. Empty means use the apex domain."
  type        = string
  default     = ""
}
variable "db_instance_class" {
  description = "RDS instance size. Review live pricing and regional availability before applying."
  type        = string
  default     = "db.t4g.micro"
}
variable "db_allocated_storage_gb" {
  type    = number
  default = 20
}
variable "db_max_allocated_storage_gb" {
  type    = number
  default = 50
}
variable "db_name" {
  type    = string
  default = "aitrading"
}
variable "db_username" {
  type    = string
  default = "aitrading_admin"
}
variable "ecs_cpu" {
  description = "Backend Fargate CPU units."
  type        = number
  default     = 512
}
variable "ecs_memory" {
  description = "Backend Fargate memory in MiB."
  type        = number
  default     = 1024
}
variable "allowed_github_repository" {
  description = "Exact owner/repository allowed to assume the deploy role."
  type        = string
  default     = "operationReact/AiTradingSystem"
}
variable "allowed_github_branch" {
  description = "Only this branch may assume the deploy role."
  type        = string
  default     = "dev"
}

variable "enable_https" {
  description = "Set true only after the Route 53 nameservers have been delegated and the ACM DNS validation records can resolve."
  type        = bool
  default     = false
}
