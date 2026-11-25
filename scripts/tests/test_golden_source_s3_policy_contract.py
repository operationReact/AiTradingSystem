"""D20-04C: offline review-only AWS permissions contract, without cloud access."""

import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
INFRA = ROOT / "infra" / "minttick-golden-source"


class GoldenSourceS3PolicyContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.files = {p.name: p.read_text(encoding="utf-8")
                     for p in INFRA.glob("*.tf")}
        cls.all = "\n".join(cls.files.values())

    def test_review_only_root_has_zero_managed_resources_or_modules(self):
        self.assertEqual(
            {"versions.tf", "variables.tf", "policies.tf", "outputs.tf"},
            set(self.files))
        self.assertNotRegex(self.all, r'(?m)^\s*resource\s+"')
        self.assertNotRegex(self.all, r'(?m)^\s*module\s+"')
        self.assertNotRegex(self.all, r'(?m)^\s*backend\s+"')
        self.assertIn('data "aws_iam_policy_document" "archive_role"', self.all)
        self.assertIn('data "aws_iam_policy_document" "bucket_policy"', self.all)

    def test_exact_account_region_prefix_and_private_kms(self):
        self.assertIn('region              = "ap-south-1"', self.all)
        self.assertIn('allowed_account_ids = ["098859530205"]', self.all)
        self.assertIn('golden-source/bronze/v1/', self.all)
        self.assertIn('kms:ViaService', self.all)
        self.assertIn('kms:CallerAccount', self.all)
        self.assertIn('s3.ap-south-1.amazonaws.com', self.all)
        self.assertIn('098859530205:key/', self.all)

    def test_role_cannot_delete_or_read_other_storage_or_submit_orders(self):
        for banned in [
            's3:DeleteObject', 's3:PutBucketPolicy', 's3:CreateBucket',
            'iam:PassRole', 'iam:AttachRolePolicy', 'kms:ScheduleKeyDeletion',
            'secretsmanager:GetSecretValue', 'ssm:GetParameter',
            'lambda:InvokeFunction', 'ec2:*'
        ]:
            self.assertNotIn(banned, self.all)
        self.assertIn('s3:GetObjectVersion', self.all)
        self.assertIn('s3:ListBucket', self.all)
        self.assertIn('s3:GetEncryptionConfiguration', self.all)
        self.assertIn('s3:GetBucketPublicAccessBlock', self.all)

    def test_bucket_policy_rejects_insecure_upload_and_other_roles(self):
        for marker in [
            'DenyAllNonTlsAccess', 'aws:SecureTransport',
            'DenyUnencryptedBronzeUploads',
            'DenyIncorrectKmsKeyOnBronzeUploads',
            'DenyOtherRolesFromGoldenBronze', 'aws:PrincipalArn',
            's3:x-amz-server-side-encryption-aws-kms-key-id',
        ]:
            self.assertIn(marker, self.all)
        self.assertIn('aws:kms', self.all)
        self.assertIn('research_worker_role_arn', self.all)


if __name__ == "__main__":
    unittest.main()
