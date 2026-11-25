# These are previews ONLY. No bucket, KMS key, IAM policy or IAM role exists
# as a result of running this Terraform root.
output "archive_role_iam_policy_json_review_only" {
  description = "Review-only scoped IAM policy JSON; attach manually after approval."
  value       = data.aws_iam_policy_document.archive_role.json
}

output "bucket_policy_json_review_only" {
  description = "Review-only bucket policy JSON; requires operations/security approval."
  value       = data.aws_iam_policy_document.bucket_policy.json
}

output "warning_no_resources_provisioned" {
  value = "D20-04C generates review-only policy JSON and never provisions or activates Golden Source."
}
