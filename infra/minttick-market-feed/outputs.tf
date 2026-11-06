output "market_feed_lambda_function_name" {
  value = aws_lambda_function.feed.function_name
}

output "market_feed_lambda_function_arn" {
  value = aws_lambda_function.feed.arn
}

output "paper_role_invoke_enabled" {
  value = var.enable_paper_invoke
}
