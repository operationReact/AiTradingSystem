"""Exact-account PAPER SSM rollout, IAM and immutable content gate, no AWS."""
from __future__ import annotations
import base64
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[2]
EC2=ROOT/"infra/ec2-paper"
CLOUDFORMATION=EC2/"bootstrap/state-preflight.cfn.json"
SPEC=importlib.util.spec_from_file_location("paper_ssm_gate",EC2/"verify_ssm_release.py")
assert SPEC and SPEC.loader
gate=importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(gate)


def approved(op):
    return {
        "kind":"ec2-paper-ssm-release-v1",
        "operation":op,
        "account":gate.ACCOUNT,
        "region":gate.REGION,
        "instance_id":gate.INSTANCE,
        "instance_type":"t4g.medium",
        "environment":"PAPER",
        "confirmation":gate.REQUESTS[op][1],
    }


def valid_aws_inputs(state="running",ssm_status="Online",schedule_state="ENABLED"):
    def fn(*args):
        if args[:2]==("sts","get-caller-identity"):
            return {"Account":gate.ACCOUNT,"Arn":
                    f"arn:aws:sts::{gate.ACCOUNT}:assumed-role/{gate.ROLE}/ci-audit"}
        if args[:2]==("ec2","describe-instances"):
            return {"Reservations":[{"Instances":[{
                "InstanceId":gate.INSTANCE,"InstanceType":"t4g.medium",
                "VpcId":gate.VPC,"SubnetId":gate.SUBNET,
                "IamInstanceProfile":{"Arn":gate.PROFILE},
                "MetadataOptions":{"HttpTokens":"required"},
                "State":{"Name":state},
                "Tags":[{"Key":k,"Value":v} for k,v in gate.TAGS.items()],
            }]}]}
        if args[:2]==("scheduler","get-schedule"):
            return {"State":schedule_state,"ScheduleExpression":"cron(0 22 * * ? *)",
                    "ScheduleExpressionTimezone":"Asia/Kolkata",
                    "Target":{"Arn":gate.STOP_TARGET,"RoleArn":gate.STOP_ROLE,
                              "Input":json.dumps({"InstanceIds":[gate.INSTANCE]})}}
        if args[:2]==("ssm","describe-instance-information"):
            return {"InstanceInformationList":[{"InstanceId":gate.INSTANCE,
                                                "PingStatus":ssm_status}]}
        raise AssertionError("Unexpected AWS call "+str(args))
    return fn


class PaperSsmRelease(unittest.TestCase):
    def test_exact_independent_one_file_request_kinds(self):
        for action in gate.REQUESTS:
            gate.validate_request(action,approved(action))
            for change in (
                {"instance_id":"i-other"},
                {"account":"other"},
                {"region":"us-east-1"},
                {"environment":"LIVE"},
                {"confirmation":"ANY"},
                {"extra":"YES"},
            ):
                with self.subTest(action=action,change=change),self.assertRaises(gate.Rejected):
                    gate.validate_request(action,{**approved(action),**change})
        with self.assertRaises(gate.Rejected):
            gate.validate_request("start",{"operation":"start"})

    def test_only_one_exact_role_and_temporary_nonpublic_release_bucket(self):
        doc=json.loads(CLOUDFORMATION.read_text(encoding="utf-8"))
        resources=doc["Resources"]
        self.assertEqual(set(resources),{
            "PaperStateBucket","PaperStateBucketPolicy",
            "ReadOnlyGithubPreflightRole","ReadOnlyGithubPlanRole",
            "RestrictedGithubProvisionRole","PaperManualGithubLifecycleRole",
            "PaperReleaseArtifactBucket","PaperReleaseArtifactBucketPolicy",
            "PaperGithubSsmDeployRole",
        })
        bucket=resources["PaperReleaseArtifactBucket"]
        self.assertEqual(bucket["Type"],"AWS::S3::Bucket")
        self.assertEqual(bucket["DeletionPolicy"],"Retain")
        props=bucket["Properties"]
        self.assertEqual(props["BucketName"],"aitrading-paper-releases-098859530205")
        self.assertEqual(set(props["PublicAccessBlockConfiguration"].values()),{True})
        self.assertEqual(props["BucketEncryption"]["ServerSideEncryptionConfiguration"][0]
                         ["ServerSideEncryptionByDefault"]["SSEAlgorithm"],"AES256")
        lifecycle=props["LifecycleConfiguration"]["Rules"][0]
        self.assertEqual(lifecycle["Prefix"],"releases/")
        self.assertEqual(lifecycle["ExpirationInDays"],3)
        self.assertEqual(lifecycle["NoncurrentVersionExpiration"]["NoncurrentDays"],3)
        bucketpolicy=resources["PaperReleaseArtifactBucketPolicy"]["Properties"]
        self.assertEqual(bucketpolicy["Bucket"],{"Ref":"PaperReleaseArtifactBucket"})
        stmts=bucketpolicy["PolicyDocument"]["Statement"]
        allow=next(x for x in stmts if x["Effect"]=="Allow")
        self.assertEqual(allow["Action"],"s3:GetObject")
        self.assertEqual(allow["Principal"],{
            "AWS":"arn:aws:iam::098859530205:role/aitrading-paper-ec2-ssm"})
        self.assertEqual(allow["Resource"],
                         "arn:aws:s3:::aitrading-paper-releases-098859530205/releases/*")

    def test_ssm_iam_scope_exact_instance_dev_only_no_broker_or_ec2_lifecycle(self):
        doc=json.loads(CLOUDFORMATION.read_text(encoding="utf-8"))
        role=doc["Resources"]["PaperGithubSsmDeployRole"]["Properties"]
        self.assertEqual(role["RoleName"],gate.ROLE)
        trust=role["AssumeRolePolicyDocument"]["Statement"][0]
        self.assertEqual(trust["Condition"]["StringEquals"],{
            "token.actions.githubusercontent.com:aud":"sts.amazonaws.com",
            "token.actions.githubusercontent.com:sub":
                "repo:operationReact@215111824/AiTradingSystem@1433875172:ref:refs/heads/dev",
        })
        p=role["Policies"][0]["PolicyDocument"]["Statement"]
        by_sid={x["Sid"]:x for x in p}
        doc_perm=by_sid["SendOnlyAWSLinuxShellSSMDocumentMumbai"]
        self.assertEqual(doc_perm["Resource"],
                         "arn:aws:ssm:ap-south-1::document/AWS-RunShellScript")
        self.assertNotIn("Condition",doc_perm)
        host_perm=by_sid["SendOnlyExactTaggedPaperInstance"]
        self.assertEqual(host_perm["Resource"],
                         "arn:aws:ec2:ap-south-1:098859530205:instance/i-09c01b470fb39b85a")
        self.assertEqual(host_perm["Condition"]["StringEquals"],{
            "ssm:resourceTag/Application":"ai-trading-system",
            "ssm:resourceTag/Environment":"paper",
        })
        schedule=by_sid["CheckExactDailyStopScheduleBeforeAnyDeployment"]
        self.assertEqual(schedule["Resource"],
                         "arn:aws:scheduler:ap-south-1:098859530205:schedule/default/aitrading-paper-nightly-stop")
        upload=by_sid["UploadOnlyShortLivedPaperReleaseAssets"]
        self.assertEqual(set(upload["Action"]),{"s3:PutObject","s3:GetObject"})
        self.assertEqual(upload["Resource"],
                         "arn:aws:s3:::aitrading-paper-releases-098859530205/releases/*")
        allows={a for p0 in p if p0["Effect"]=="Allow" for a in
                (p0["Action"] if isinstance(p0["Action"],list) else [p0["Action"]])}
        for action in ("ec2:StartInstances","ec2:StopInstances","ec2:RunInstances",
                       "ec2:TerminateInstances","iam:PassRole","iam:UpdateRole",
                       "ssm:StartSession","s3:DeleteObject","rds:CreateDBInstance"):
            self.assertNotIn(action,allows)
        self.assertEqual(by_sid["ExplicitlyDenyCloudProvisionAndTrades"]["Effect"],"Deny")

    def test_actual_aws_identity_host_schedule_and_ssm_online_required(self):
        with patch.dict(os.environ,{"AWS_REGION":gate.REGION}):
            with patch.object(gate,"aws_json",side_effect=valid_aws_inputs()):
                gate.verify_aws()
            # Explicitly disabled by the owner: safe to deploy to this
            # exact host without quietly re-enabling the EC2 cost stop.
            with patch.object(gate,"aws_json",side_effect=valid_aws_inputs(
                    schedule_state="DISABLED")):
                gate.verify_aws()
            with patch.object(gate,"aws_json",side_effect=valid_aws_inputs(
                    schedule_state="DELETING")):
                with self.assertRaises(gate.Rejected):
                    gate.verify_aws()
            for states in (("stopped","Online"),("running","ConnectionLost")):
                with self.subTest(states=states):
                    with patch.object(gate,"aws_json",side_effect=valid_aws_inputs(*states)):
                        with self.assertRaises(gate.Rejected):
                            gate.verify_aws()

    def test_remote_command_only_signed_commit_sha_and_bounded_scripts(self):
        for action in gate.REQUESTS:
            command=gate.make_command(action,"a"*40,"b"*64)
            self.assertIn("base64 -d | bash -s --",command)
            self.assertNotIn("AWS_SECRET_ACCESS_KEY",command)
            self.assertNotIn("TRADING_MODE=LIVE",command)
            b64=command.split("'")[3]
            decoded=base64.b64decode(b64).decode("utf-8")
            self.assertIn("#!/usr/bin/env bash",decoded)
            self.assertNotIn("aws ec2 start-instances",decoded)
            self.assertNotIn("aws ec2 terminate-instances",decoded)
        for sha, digest in [("a"*39,"b"*64),("a"*40,"b"*63),
                             ("hello","world"),("a"*40,"b"*63+"Z")]:
            with self.subTest(sha=sha),self.assertRaises(gate.Rejected):
                gate.make_command("deploy",sha,digest)

    def test_nonpublic_host_release_exact_sha256_and_no_secret_output(self):
        host=(EC2/"ssm_apply_release.sh").read_text()
        for keyword in (
            "sha256sum -c -", "tar -tf", "images.tar.gz",
            "runtime.env", "docker load", "run_paper.sh",
            'releases/$sha/$expected_hash/release.tar',
            '[[ "$expected_hash" =~ ^[0-9a-f]{64}$ ]]',
        ):
            with self.subTest(keyword=keyword):self.assertIn(keyword,host)
        self.assertNotIn("docker volume rm",host)
        self.assertNotIn("aws ec2 start-instances",host)
        for name in ("ssm_apply_release.sh","ssm_preflight.sh"):
            result=subprocess.run(["bash","-n",str(EC2/name)],capture_output=True,text=True)
            self.assertEqual(result.returncode,0,result.stderr)

    def test_ssm_jobs_only_dev_one_file_approval_and_immutable_digest(self):
        for name in ("ec2-paper-ssm-readiness.yml","ec2-paper-ssm-app-deploy.yml"):
            s=(ROOT/".github/workflows"/name).read_text()
            for required in (
                "branches: [dev]",
                "id-token: write", "contents: read",
                "git diff-tree --no-commit-id --name-only -r HEAD",
                "verify_ssm_release.py",
                "cancel-in-progress: false", "queue: max",
                "group: aitrading-paper-ec2-manual-lifecycle",
                "role/aitrading-paper-ec2-ssm-deploy",
            ):
                self.assertIn(required,s)
            for forbidden in ("workflow_dispatch:", "aws ec2 start-instances",
                              "aws ec2 run-instances","aws ec2 terminate-instances",
                              "terraform apply","TRADING_MODE=LIVE","secrets.",
                              "ssh ","scp ","--push", "docker push"):
                with self.subTest(workflow=name,forbidden=forbidden):
                    self.assertNotIn(forbidden,s)
        deploy=(ROOT/".github/workflows/ec2-paper-ssm-app-deploy.yml").read_text()
        for required in ("docker buildx build --platform linux/arm64 --load",
                         "--file backend/Dockerfile.ec2-paper-arm64",
                         "flutter build web --release",
                         "sha256sum", "docker save",
                         "aws s3 cp", "--sse AES256",
                         "verify_ssm_release.py run deploy"):
            self.assertIn(required,deploy)


if __name__=="__main__":
    unittest.main()
