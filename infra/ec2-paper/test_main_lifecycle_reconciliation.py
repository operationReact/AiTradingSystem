"""No-cloud regression for the reconciled manual lifecycle capacity policy."""
import unittest
from unittest.mock import patch
import control_paper_host as host


class MainLifecycleReconciliationTest(unittest.TestCase):
    def instance(self, size):
        return {"Reservations": [{"Instances": [{
            "InstanceId": host.INSTANCE, "InstanceType": size,
            "VpcId": host.VPC, "SubnetId": host.SUBNET,
            "MetadataOptions": {"HttpTokens": "required"},
            "IamInstanceProfile": {"Arn": host.PROFILE},
            "Tags": [{"Key": k, "Value": v} for k, v in host.TAGS.items()],
            "State": {"Name": "stopped"},
        }]}]}

    def test_exact_approved_capacity_options(self):
        for size in ("t4g.medium", "m6g.medium", "m7g.medium"):
            with self.subTest(size=size), patch.object(host, "aws_json", return_value=self.instance(size)):
                self.assertEqual(host.describe(), "stopped")

    def test_other_sizes_and_instance_identity_rejected(self):
        for size in ("t4g.large", "m6i.medium", "t3.medium", None):
            with self.subTest(size=size), patch.object(host, "aws_json", return_value=self.instance(size)):
                with self.assertRaises(host.ControlError):
                    host.describe()
        response = self.instance("m6g.medium")
        response["Reservations"][0]["Instances"][0]["InstanceId"] = "wrong"
        with patch.object(host, "aws_json", return_value=response):
            with self.assertRaises(host.ControlError):
                host.describe()


if __name__ == "__main__":
    unittest.main()
