# PAPER automation acceptance test matrix

| Scenario | Expected result |
| --- | --- |
| New account | PAPER + Manual, LIVE unavailable |
| Manual PAPER within risk | Accepted through shared risk boundary |
| Automated PAPER without opt-in | Rejected |
| Automated PAPER opted-in + eligible + sufficient confidence/risk | Accepted |
| Duplicate manual/AI intent | No duplicate accepted order/fill |
| Capital exceeded | Rejected with customer-readable message |
| Risk budget exceeded | Rejected |
| Missing/invalid stop or target | Rejected |
| Poor reward/risk | Rejected |
| Emergency stop before intent | Manual and automated intents rejected |
| Emergency stop with open position | Position eligible for safety exit |
| Target reached | Position closes, realized P&L recorded |
| Stop reached | Position closes, realized P&L recorded |
| Price update without exit | Unrealized P&L updates |
| Request LIVE mode | Fail closed; PAPER remains active |
| Broker submission policy | False for PAPER and LIVE in this release |
| UI language | No raw internal/provider/operator vocabulary |
| Phone visual acceptance | Matches canonical Neon/Clean product design |

Persistence and existing production service integration must be demonstrated before this matrix can be considered release-complete.
