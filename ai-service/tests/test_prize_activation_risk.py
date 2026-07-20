import unittest

from app.services.risk_engine import high_risk_override, rule_score


class PrizeActivationRiskTest(unittest.TestCase):
    def test_lottery_activation_fee_is_high_risk(self):
        text = "短信告诉我我中了一百万彩票让我给他一万块钱激活"

        score, hits = rule_score(text)
        override_score, reason = high_risk_override(text, hits)

        self.assertGreaterEqual(score, 0.2)
        self.assertIn("prize_info", hits)
        self.assertEqual(override_score, 0.86)
        self.assertIn("中奖领奖", reason)


if __name__ == "__main__":
    unittest.main()
