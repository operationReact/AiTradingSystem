# PAPER automation risk examples

Examples are illustrative acceptance cases, not investment advice or production limits.

## Accepted example

Entry ₹100, stop ₹95, target ₹110, quantity 10, available PAPER capital ₹10,000, max risk ₹600. Notional ₹1,000 and entry-to-stop risk ₹50. Reward ₹100. The foundation risk boundary accepts this example.

## Buying-power rejection

Entry ₹100, quantity 100, available PAPER capital ₹1,000. Notional ₹10,000 exceeds buying power, so no PAPER order/fill/position is created.

## Risk-budget rejection

Entry ₹100, stop ₹80, quantity 10, max risk ₹100. Entry-to-stop risk is ₹200, so the intent is rejected.

## Reward/risk rejection

Entry ₹100, stop ₹90, target ₹105, quantity 10. Potential reward is smaller than entry-to-stop risk, so the intent is rejected.

## Emergency stop

Even an otherwise acceptable Manual or Automated intent is rejected while Trading paused is authoritative.
