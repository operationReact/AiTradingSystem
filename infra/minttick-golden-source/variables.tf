variable "bucket_name" {
  description = "Independently provisioned, dedicated, private ap-south-1 archival bucket; required for policy JSON preview only."
  type        = string

  validation {
    condition     = can(regex("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$", var.bucket_name)) && !strcontains(var.bucket_name, "..")
    error_message = "Use a valid exact-purpose S3 bucket name."
  }
}

variable "kms_key_arn" {
  description = "Pinned customer-managed KMS key in 098859530205 / ap-south-1; required for policy JSON preview only."
  type        = string

  validation {
    condition     = can(regex("^arn:aws:kms:ap-south-1:098859530205:key/[A-Za-z0-9-]{1,100}$", var.kms_key_arn))
    error_message = "Only a same-account ap-south-1 KMS key ARN is permitted."
  }
}

variable "research_worker_role_arn" {
  description = "Explicitly reviewed read-only Golden Source research worker IAM role ARN; never the live order execution role."
  type        = string

  validation {
    condition     = can(regex("^arn:aws:iam::098859530205:role/[A-Za-z0-9_+=,.@/-]{1,128}$", var.research_worker_role_arn))
    error_message = "Must use a dedicated, reviewed workload role in the target account."
  }
}

locals {
  source_bucket_arn = "arn:aws:s3:::${var.bucket_name}"
  bronze_prefix     = "golden-source/bronze/v1/"
  bronze_object_arn = "${local.source_bucket_arn}/${local.bronze_prefix}*"
}
