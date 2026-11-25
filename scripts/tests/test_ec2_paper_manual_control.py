"""No AWS access: manual PAPER EC2 Start/Stop/Status and OIDC safety."""
from __future__ import annotations

import importlib.util
import json
import os
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "infra/ec2-paper/control_paper_host.py"
WORKFLOW = ROOT / ".github/workflows/paper-ec2-manual-control.yml"
CFN = ROOT / "infra/ec2-paper/bootstrap/state-preflight.cfn.json"
spec = importlib.util.spec_from_file_location("paper_manual", SOURCE)
assert spec and spec.loader
ctrl = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ctrl)


def instance(state: str, *, wrong_tags: bool = False) -> dict:
    tags = ctrl.TAGS.copy()
    if wrong_tags:
        tags["Environment"] = "live"
    return {
        "Reservations": [{"Instances": [{
            "InstanceId": ctrl.INSTANCE,
            "InstanceType": "t4g.medium",
            "VpcId": ctrl.VPC,
            "SubnetId": ctrl.SUBNET,
            "IamInstanceProfile": {"Arn": ctrl.PROFILE},
            "MetadataOptions": {"HttpTokens": "required"},
            "Tags": [{"Key": k, "Value": v} for k, v in tags.items()],
            "State": {"Name": state},
        }]}],
    }


def schedule(state: str = "ENABLED") -> dict:
    return {
        "State": state,
        "ScheduleExpression": "cron(0 22 * * ? *)",
        "ScheduleExpressionTimezone": "Asia/Kolkata",
        "Target": {
            "Arn": ctrl.STOP_ACTION,
            "RoleArn": ctrl.STOP_ROLE,
            "Input": json.dumps({"InstanceIds": [ctrl.INSTANCE]}),
        },
    }


class FakeAws:
    def __init__(self, states: list[str], *, valid_schedule: bool = True,
                 wrong_tags: bool = False, race: bool = False,
                 schedule_state: str = "ENABLED"):
        self.states = iter(states)
        self.schedule_ok = valid_schedule
        self.schedule_state = schedule_state
        self.wrong_tags = wrong_tags
        self.race = race
        self.commands: list[tuple[str, ...]] = []

    def __call__(self, *args: str) -> dict:
        self.commands.append(args)
        if args[:2] == ("sts", "get-caller-identity"):
            return {"Account": ctrl.ACCOUNT}
        if args[:2] == ("ec2", "describe-instances"):
            return instance(next(self.states), wrong_tags=self.wrong_tags)
        if args[:2] == ("scheduler", "get-schedule"):
            if not self.schedule_ok:
                raise ctrl.AwsError("ResourceNotFoundException")
            return schedule(self.schedule_state)
        if args[:2] in (("ec2", "start-instances"), ("ec2", "stop-instances")):
            if self.race:
                self.race = False
                raise ctrl.AwsError("IncorrectInstanceState")
            return {"Transition": "accepted"}
        raise AssertionError(f"Unexpected AWS command: {args}")


def evaluate(op: str, states: list[str], **kwargs):
    fake = FakeAws(states, **kwargs)
    with patch.dict(os.environ, {"AWS_REGION": "ap-south-1"}):
        with patch.object(ctrl, "aws_json", side_effect=fake):
            with patch.object(ctrl.time, "sleep", return_value=None):
                response = ctrl.apply(op)
    return response, fake.commands


class ManualPaperControlTests(unittest.TestCase):
    def test_start_from_stopped_exactly_once(self):
        (state, detail), commands = evaluate("start", ["stopped", "pending", "running"])
        self.assertEqual(state, "running")
        self.assertEqual(detail, "Transition complete")
        self.assertEqual(sum(c[:2] == ("ec2", "start-instances") for c in commands), 1)
        self.assertEqual(sum(c[:2] == ("scheduler", "get-schedule") for c in commands), 1)
        self.assertFalse(any(c[:2] == ("ec2", "stop-instances") for c in commands))

    def test_disabled_nightly_stop_allows_start_for_24x7_history(self):
        (state, detail), commands = evaluate(
            "start", ["stopped", "pending", "running"], schedule_state="DISABLED")
        self.assertEqual(state, "running")
        self.assertEqual(detail, "Transition complete")
        self.assertEqual(sum(c[:2] == ("ec2", "start-instances") for c in commands), 1)
        self.assertEqual(sum(c[:2] == ("scheduler", "get-schedule") for c in commands), 1)

    def test_unknown_schedule_state_blocks_start(self):
        fake = FakeAws(["stopped"], schedule_state="UNKNOWN")
        with patch.dict(os.environ, {"AWS_REGION": "ap-south-1"}), (
            patch.object(ctrl, "aws_json", side_effect=fake)
        ):
            with self.assertRaises(ctrl.ControlError):
                ctrl.apply("start")
        self.assertFalse(any(c[:2] == ("ec2", "start-instances") for c in fake.commands))

    def test_repeated_start_running_is_noop(self):
        (state, detail), commands = evaluate("start", ["running"])
        self.assertEqual(state, "running")
        self.assertIn("no-op", detail)
        self.assertFalse(any(c[:2] == ("ec2", "start-instances") for c in commands))

    def test_repeated_stop_stopped_is_noop(self):
        (state, detail), commands = evaluate("stop", ["stopped"])
        self.assertEqual(state, "stopped")
        self.assertIn("no-op", detail)
        self.assertFalse(any(c[:2] == ("ec2", "stop-instances") for c in commands))

    def test_stop_from_running_exactly_once(self):
        (state, detail), commands = evaluate("stop", ["running", "stopping", "stopped"])
        self.assertEqual(state, "stopped")
        self.assertEqual(detail, "Transition complete")
        self.assertEqual(sum(c[:2] == ("ec2", "stop-instances") for c in commands), 1)

    def test_stop_when_instance_pending_waits_then_stops(self):
        (_, _), commands = evaluate(
            "stop", ["pending", "pending", "running", "stopping", "stopped"])
        self.assertEqual(sum(c[:2] == ("ec2", "stop-instances") for c in commands), 1)

    def test_start_when_instance_stopping_waits_then_starts(self):
        (state, _), commands = evaluate(
            "start", ["stopping", "stopping", "stopped", "pending", "running"])
        self.assertEqual(state, "running")
        self.assertEqual(sum(c[:2] == ("ec2", "start-instances") for c in commands), 1)

    def test_race_incorrect_state_rechecks_without_duplicating_command(self):
        (state, _), commands = evaluate(
            "start", ["stopped", "pending", "running"], race=True)
        self.assertEqual(state, "running")
        self.assertEqual(sum(c[:2] == ("ec2", "start-instances") for c in commands), 1)

    def test_schedule_missing_blocks_start_before_ec2_api(self):
        fake = FakeAws(["stopped"], valid_schedule=False)
        with patch.dict(os.environ, {"AWS_REGION": "ap-south-1"}), (
            patch.object(ctrl, "aws_json", side_effect=fake)
        ):
            with self.assertRaises(ctrl.ControlError):
                ctrl.apply("start")
        self.assertFalse(any(c[:2] == ("ec2", "start-instances") for c in fake.commands))

    def test_wrong_instance_tags_refuse_mutation(self):
        fake = FakeAws(["stopped"], wrong_tags=True)
        with patch.dict(os.environ, {"AWS_REGION": "ap-south-1"}), (
            patch.object(ctrl, "aws_json", side_effect=fake)
        ):
            with self.assertRaises(ctrl.ControlError):
                ctrl.apply("start")
        self.assertFalse(any(c[:2] == ("ec2", "start-instances") for c in fake.commands))

    def test_unsupported_states_and_operations_fail_closed(self):
        for op in ("terminate", "reboot", "", "--start"):
            with self.subTest(op=op), self.assertRaises(ctrl.ControlError):
                ctrl.apply(op)
        fake = FakeAws(["terminated"])
        with patch.dict(os.environ, {"AWS_REGION": "ap-south-1"}), (
            patch.object(ctrl, "aws_json", side_effect=fake)
        ):
            with self.assertRaises(ctrl.ControlError):
                ctrl.apply("stop")

    def test_status_read_only_even_if_scheduler_missing(self):
        (state, detail), commands = evaluate("status", ["stopped"], valid_schedule=False)
        self.assertEqual(state, "stopped")
        self.assertEqual(detail, "Read-only status")
        self.assertFalse(any(c[:2] in (
            ("ec2", "start-instances"), ("ec2", "stop-instances")) for c in commands))

    def test_main_branch_manual_workflow_queues_many_requests(self):
        text = WORKFLOW.read_text(encoding="utf-8")
        for token in (
            "workflow_dispatch:", "type: choice",
            "- status", "- start", "- stop",
            "github.ref == 'refs/heads/main'",
            "queue: max", "cancel-in-progress: false",
            "id-token: write", "contents: read",
            "arn:aws:iam::098859530205:role/aitrading-paper-ec2-manual-control",
            'python3 infra/ec2-paper/control_paper_host.py "$REQUESTED_OPERATION"',
        ):
            with self.subTest(token=token):
                self.assertIn(token, text)
        for disallowed in (
            "terraform apply", "terraform destroy", "aws ec2 run-instances",
            "aws ec2 terminate-instances", "aws ec2 reboot-instances",
            "secrets.", "TRADING_MODE=LIVE", "cancel-in-progress: true",
        ):
            with self.subTest(disallowed=disallowed):
                self.assertNotIn(disallowed, text)

    def test_only_one_exact_instance_iam_and_main_branch_oidc(self):
        data = json.loads(CFN.read_text(encoding="utf-8"))
        role = data["Resources"]["PaperManualGithubLifecycleRole"]["Properties"]
        self.assertEqual(role["RoleName"], "aitrading-paper-ec2-manual-control")
        trust = role["AssumeRolePolicyDocument"]["Statement"][0]
        self.assertEqual(trust["Condition"]["StringEquals"], {
            "token.actions.githubusercontent.com:aud": "sts.amazonaws.com",
            "token.actions.githubusercontent.com:sub":
                "repo:operationReact@215111824/AiTradingSystem@1433875172:ref:refs/heads/main",
        })
        statements = role["Policies"][0]["PolicyDocument"]["Statement"]
        by_sid = {s["Sid"]: s for s in statements}
        start_stop = by_sid["StartStopOnlyTaggedExistingPaperEC2"]
        self.assertEqual(set(start_stop["Action"]),
                         {"ec2:StartInstances", "ec2:StopInstances"})
        self.assertEqual(start_stop["Resource"],
                         "arn:aws:ec2:ap-south-1:098859530205:instance/i-09c01b470fb39b85a")
        self.assertEqual(start_stop["Condition"]["StringEquals"], {
            "aws:RequestedRegion": "ap-south-1",
            "ec2:ResourceTag/Application": "ai-trading-system",
            "ec2:ResourceTag/Environment": "paper",
            "ec2:ResourceTag/Name": "aitradingsystem-paper-ec2",
        })
        self.assertEqual(by_sid["ReadExactDailyStopSchedule"]["Resource"],
                         "arn:aws:scheduler:ap-south-1:098859530205:schedule/default/aitrading-paper-nightly-stop")
        self.assertEqual(by_sid["ExplicitlyDenyAnyInstanceCreationTerminationOrReboot"]["Effect"],
                         "Deny")
        self.assertEqual(set(by_sid["ExplicitlyDenyAnyInstanceCreationTerminationOrReboot"]["Action"]),
                         {"ec2:RunInstances", "ec2:TerminateInstances", "ec2:RebootInstances"})
        all_actions = {a for s in statements if s["Effect"] == "Allow"
                       for a in (s["Action"] if isinstance(s["Action"], list) else [s["Action"]])}
        for disallowed in ("iam:PassRole", "ec2:RunInstances",
                           "ec2:TerminateInstances", "rds:StartDBInstance", "s3:PutObject",
                           "scheduler:UpdateSchedule"):
            self.assertNotIn(disallowed, all_actions)


if __name__ == "__main__":
    unittest.main()
