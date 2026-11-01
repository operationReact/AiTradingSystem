# ECS runtime desired counts and deployed task revisions are owned by the deployment workflows.
# Terraform provisions the service shells and must not scale a deployed application down
# or roll it back to the bootstrap task definition during infrastructure reconciliation.

resource "aws_ecs_service" "backend" {
  lifecycle {
    ignore_changes = [desired_count, task_definition]
  }
}

resource "aws_ecs_service" "frontend" {
  lifecycle {
    ignore_changes = [desired_count, task_definition]
  }
}
