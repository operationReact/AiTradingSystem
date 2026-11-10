# D20-04C outputs review-only policy JSON; nothing is attached or created.
# No AWS resources, data-source market access, keys, secrets or execution role.

data "aws_iam_policy_document" "archive_role" {
  statement {
    sid    = "ReadOnlyExactBucketPosture"
    effect = "Allow"

    actions = [
      "s3:GetBucketLocation",
      "s3:GetBucketVersioning",
      "s3:GetEncryptionConfiguration",
      "s3:GetBucketPublicAccessBlock",
      "s3:ListBucket",
    ]

    resources = [local.source_bucket_arn]
  }

  statement {
    sid    = "ReadWriteOnlyGoldenBronzeObjectsNoDeletes"
    effect = "Allow"

    actions = [
      "s3:GetObject",
      "s3:GetObjectVersion",
      "s3:PutObject",
    ]

    resources = [local.bronze_object_arn]
  }

  statement {
    sid    = "PermitOnlyPinnedS3KmsKeyViaService"
    effect = "Allow"

    actions = [
      "kms:DescribeKey",
      "kms:Encrypt",
      "kms:Decrypt",
      "kms:GenerateDataKey",
    ]

    resources = [var.kms_key_arn]

    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = ["s3.ap-south-1.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "kms:CallerAccount"
      values   = ["098859530205"]
    }
  }
}

data "aws_iam_policy_document" "bucket_policy" {
  statement {
    sid     = "DenyAllNonTlsAccess"
    effect  = "Deny"
    actions = ["s3:*"]
    resources = [
      local.source_bucket_arn,
      "${local.source_bucket_arn}/*",
    ]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }

  statement {
    sid     = "DenyUnencryptedBronzeUploads"
    effect  = "Deny"
    actions = ["s3:PutObject"]

    resources = [local.bronze_object_arn]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "StringNotEquals"
      variable = "s3:x-amz-server-side-encryption"
      values   = ["aws:kms"]
    }
  }

  statement {
    sid     = "DenyIncorrectKmsKeyOnBronzeUploads"
    effect  = "Deny"
    actions = ["s3:PutObject"]

    resources = [local.bronze_object_arn]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "StringNotEquals"
      variable = "s3:x-amz-server-side-encryption-aws-kms-key-id"
      values   = [var.kms_key_arn]
    }
  }

  statement {
    sid     = "DenyOtherRolesFromGoldenBronze"
    effect  = "Deny"
    actions = ["s3:GetObject", "s3:GetObjectVersion", "s3:PutObject"]

    resources = [local.bronze_object_arn]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "ArnNotEquals"
      variable = "aws:PrincipalArn"
      values   = [var.research_worker_role_arn]
    }
  }
}
