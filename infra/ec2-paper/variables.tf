variable "aws_region" {
  description = "Mumbai is selected for lower pilot EC2 compute prices and India users; no existing resource migration."
  type        = string
  default     = "ap-south-1"
  validation {
    condition     = var.aws_region == "ap-south-1"
    error_message = "Only the separately reviewed Mumbai PAPER region is permitted."
  }
}

variable "approve_paid_provision" {
  description = "The only value enabling creation of any EC2 PAPER resources is explicitly typed approval. Empty = zero resources."
  type        = string
  default     = ""
  sensitive   = false
  validation {
    condition     = contains(["", "PROVISION_PAPER_EC2_4GB"], var.approve_paid_provision)
    error_message = "Approval must be empty or exactly PROVISION_PAPER_EC2_4GB."
  }
}

variable "instance_type" {
  description = "Fixed 4 GiB ARM64 Graviton pilot; requires multi-architecture Docker image validation before app rollout."
  type        = string
  default     = "t4g.medium"
  validation {
    condition     = contains(["t4g.medium", "m6g.medium", "m7g.medium"], var.instance_type)
    error_message = "Only a reviewed 4 GiB ARM64 type is permitted."
  }
}

variable "root_volume_gib" {
  description = "Persistent encrypted gp3 root+PostgreSQL Docker volume; snapshots/backups not yet implemented."
  type        = number
  default     = 35
  validation {
    condition     = var.root_volume_gib >= 30 && var.root_volume_gib <= 50 && floor(var.root_volume_gib) == var.root_volume_gib
    error_message = "Use an integer gp3 volume of 30 to 50 GiB."
  }
}

variable "daily_stop_hour_ist" {
  description = "Automatic nightly stop hour in Asia/Kolkata. No automatic start; operator starts on demand."
  type        = number
  default     = 22
  validation {
    condition     = var.daily_stop_hour_ist >= 0 && var.daily_stop_hour_ist <= 23 && floor(var.daily_stop_hour_ist) == var.daily_stop_hour_ist
    error_message = "Hour must be integer from 0 through 23."
  }
}

variable "daily_stop_enabled" {
  description = "Opt-in nightly stop only; disabled by default for unattended 24/7 owner history collection."
  type        = bool
  default     = false
}
