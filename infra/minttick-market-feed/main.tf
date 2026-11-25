locals {
  account_id    = "098859530205"
  aws_region    = "ap-south-1"
  function_name = "minttick-dev-angelone-feed"
  ssm_path      = "/minttick/dev/angelone"
  ssm_names = [
    "${local.ssm_path}/api-key",
    "${local.ssm_path}/client-code",
    "${local.ssm_path}/login-pin",
    "${local.ssm_path}/totp-secret",
  ]
  ssm_arns = [
    for name in local.ssm_names :
    "arn:aws:ssm:${local.aws_region}:${local.account_id}:parameter${name}"
  ]
}

data "archive_file" "read_only_feed" {
  type        = "zip"
  source_file = "${path.module}/../../market-feed/lambda_function.py"
  output_path = "${path.module}/minttick-feed.zip"
}

resource "aws_iam_role" "feed" {
  name = "minttick-dev-angelone-feed-lambda"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Action    = "sts:AssumeRole"
      Principal = { Service = "lambda.amazonaws.com" }
    }]
  })
}

resource "aws_iam_role_policy" "read_only_ssm" {
  name = "minttick-only-four-angelone-securestrings"
  role = aws_iam_role.feed.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = concat([
      {
        Sid      = "OnlyFourReadOnlyParameters"
        Effect   = "Allow"
        Action   = ["ssm:GetParameters"]
        Resource = local.ssm_arns
      },
      {
        Sid      = "MinimalCloudWatchLogEvents"
        Effect   = "Allow"
        Action   = ["logs:CreateLogStream", "logs:PutLogEvents"]
        Resource = "${aws_cloudwatch_log_group.feed.arn}:*"
      }
      ], var.ssm_customer_kms_key_arn == "" ? [] : [{
        Sid      = "DecryptOnlyViaMumbaiSSM"
        Effect   = "Allow"
        Action   = ["kms:Decrypt"]
        Resource = var.ssm_customer_kms_key_arn
        Condition = {
          StringEquals = {
            "kms:ViaService" = "ssm.ap-south-1.amazonaws.com"
          }
        }
    }])
  })
}

resource "aws_cloudwatch_log_group" "feed" {
  name              = "/aws/lambda/${local.function_name}"
  retention_in_days = 3
}

# This AWS account must retain at least 10 unreserved executions. An explicit
# Lambda reservation fails that quota; the function has IAM-only invocation,
# no public URL or triggers, and owner-gated read-only broker operations.
resource "aws_lambda_function" "feed" {
  function_name                  = local.function_name
  description                    = "MintTick read-only private Angel One quotes and candles"
  role                           = aws_iam_role.feed.arn
  runtime                        = "python3.12"
  handler                        = "lambda_function.handler"
  filename                       = data.archive_file.read_only_feed.output_path
  source_code_hash               = data.archive_file.read_only_feed.output_base64sha256
  memory_size                    = 256
  timeout                        = 25
  reserved_concurrent_executions = -1
  architectures                  = ["arm64"]
  depends_on                     = [aws_iam_role_policy.read_only_ssm, aws_cloudwatch_log_group.feed]
}
