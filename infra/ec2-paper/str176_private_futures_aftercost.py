#!/usr/bin/env python3
"""Real owner-private expired-futures EOD retrospective STR-176 oracle.
Reads original HTTP archive only; uses explicit hypothetical open fills/costs.
Does NOT claim original first-publication timing or actual broker fills.
"""
from __future__ import annotations
import base64,gzip,hashlib,json,os,re,sqlite3,sys,time
from datetime import date,datetime,timedelta
from decimal import Decimal,ROUND_HALF_UP
from pathlib import Path
import urllib.parse

DB=Path("/opt/aitradingsystem-paper/fno-history/str176-private-futures-60day.sqlite")
INSTANCE="i-09c01b470fb39b85a"
REQUEST=Path("infra/ec2-paper/requests/str176-private-futures-60day.json")
MARKER="MINTTICK_STR176_SOURCE_BACKTEST:"
SOURCE_REQUEST={
 "kind":"minttick-str176-owner-futures-60day-private-v1",
 "account":"098859530205","region":"ap-south-1","instance_id":INSTANCE,
 "max_contract_months":12,"max_provider_gets":25,
 "no_market_export":True,"no_orders":True,"no_rights_change":True}
ROUND_TRIP_BPS=40

class InvalidSource(Exception):pass

def parse_packet(raw,key,expiry,rows_expected):
    from fno_history_backfill import Batch,validate_candles
    first=expiry-timedelta(days=60)
    try:
        doc=json.loads(raw)
        bars=validate_candles(doc["data"]["candles"],
            Batch(0,key,"day",first,expiry,1))
        if doc.get("status")!="success" or len(bars)!=rows_expected:
            raise ValueError("INCOMPLETE_ORIGINAL_HTTP_ROWS")
        result=sorted(bars,key=lambda b:b[0])
        if len({x[0] for x in result})!=len(result):
            raise ValueError("DUPLICATE_EXCHANGE_SESSION")
        return result
    except (KeyError,ValueError,TypeError,UnicodeError):
        raise InvalidSource("ORIGINAL_HTTP_ROWS_INVALID") from None

def signal(seven, expiry, decision_day):
    """Independent implementation of STR-176 fixed v1 threshold math."""
    if len(seven)!=7 or not 7<=(expiry-decision_day).days<=120:
        return "INVALID"
    parsed=[];last=None
    for source in seven:
        dt=datetime.fromisoformat(source[0]).date()
        if last and not 1<=(dt-last).days<=5:return "INVALID"
        last=dt
        try:
            op,hi,lo,close=map(lambda x:Decimal(str(x)),source[1:5])
            vol=int(source[5]);oi=int(source[6])
            if (min(op,hi,lo,close)<=0 or lo>min(op,close)
                    or hi<max(op,close) or vol<0 or oi<=0
                    or (hi-lo)*10000/close>800):
                return "INVALID"
        except (ValueError,TypeError,ArithmeticError):
            return "INVALID"
        parsed.append((op,hi,lo,close,vol,oi))
    closes=[x[3] for x in parsed]
    if any(abs(closes[i]-closes[i-1])*10000/closes[i-1]>2500
           for i in range(1,7)):return "INVALID"
    baseline=sum(x[4] for x in parsed[:3])
    recent=sum(x[4] for x in parsed[4:])
    if baseline<=0:return "INVALID"
    participation=Decimal(parsed[-1][4])*10000/Decimal(parsed[-1][5])
    if Decimal(recent)*10000/Decimal(baseline)<11000 or participation<2000:
        return "NO_SIGNAL"
    last3=[closes[i]-closes[i-1] for i in range(4,7)]
    rises=sum(x>0 for x in last3);falls=sum(x<0 for x in last3)
    prevHigh=max(x[1] for x in parsed[:6])
    prevLow=min(x[2] for x in parsed[:6])
    up=(closes[-1]-prevHigh)*10000/prevHigh
    down=(prevLow-closes[-1])*10000/prevLow
    if up>=100 and rises>=2 and falls==0 and up-20>30:return "UP"
    if down>=100 and falls>=2 and rises==0 and down-20>30:return "DOWN"
    return "NO_SIGNAL"

def replay(contracts):
    """Chronological 8-month train vs remaining-month OOS with fixed costs.
    Fills are strictly modeled at the open TWO sessions after a signal bar,
    and closed at the NEXT session's open. Never same-bar fills.
    """
    if not 1<=len(contracts)<=12:
        raise InvalidSource("CONTRACT_SOURCE_COUNT_INVALID")
    train_decisions=oos_decisions=signals=train_trades=oos_trades=invalid=0
    trainNet=oosGross=oosNet=equity=peak=drawdown=Decimal(0)
    for ix,(key,expiry,lot,original,digest,firstCaptured,expected) in enumerate(contracts):
        exp=date.fromisoformat(expiry)
        if (not re.fullmatch(r"NSE_FO\|[A-Za-z0-9_-]+\|\d{2}-\d{2}-\d{4}",key)
                or key.rsplit("|",1)[-1]!=exp.strftime("%d-%m-%Y")
                or not 1<=lot<=100000 or firstCaptured<=0
                or hashlib.sha256(original).hexdigest()!=digest):
            raise InvalidSource("CONTRACT_OR_CUSTODY_UNVERIFIED")
        bars=parse_packet(original,key,exp,expected)
        if not 10<=len(bars)<=65:
            raise InvalidSource("ORIGINAL_CONTRACT_SESSION_COUNT_INVALID")
        for i in range(6,len(bars)-3):
            day=datetime.fromisoformat(bars[i+2][0]).date()
            outcome=signal(bars[i-6:i+1],exp,day)
            if outcome=="INVALID":
                invalid+=1;continue
            if ix < 8:train_decisions+=1
            else:oos_decisions+=1
            if outcome=="NO_SIGNAL":continue
            signals+=1
            entry=Decimal(str(bars[i+2][1]))
            exit=Decimal(str(bars[i+3][1]))
            multiplier=Decimal(1 if outcome=="UP" else -1)
            gross=(exit-entry)*Decimal(lot)*multiplier*100
            # Explicit fixed 40bps total: brokerage/exchange/tax plus
            # spread/impact/slippage. Research cost assumption, not a fill.
            cost=entry*Decimal(lot)*Decimal(ROUND_TRIP_BPS)/10000*100
            net=gross-cost
            if ix<8:train_trades+=1;trainNet+=net
            else:
                oos_trades+=1;oosGross+=gross;oosNet+=net
                equity+=net;peak=max(peak,equity)
                drawdown=max(drawdown,peak-equity)
    return {
       "state":"REAL_SOURCE_SIMULATED_AFTERCOST_NOT_CERTIFIED",
       "strategy":"STR-176","futuresContracts":len(contracts),
       "trainingDecisions":train_decisions,"oosDecisions":oos_decisions,
       "signalCandidates":signals,"invalidSourceWindows":invalid,
       "trainingSimulatedTrades":train_trades,"oosSimulatedTrades":oos_trades,
       "trainingNetPaise":int(trainNet.to_integral_value(rounding=ROUND_HALF_UP)),
       "oosGrossPaise":int(oosGross.to_integral_value(rounding=ROUND_HALF_UP)),
       "oosNetPaise":int(oosNet.to_integral_value(rounding=ROUND_HALF_UP)),
       "oosMaxDrawdownPaise":int(drawdown.to_integral_value(rounding=ROUND_HALF_UP)),
       "roundTripCostBps":ROUND_TRIP_BPS,
       "independentHistoricalE2ECertified":0,"brokerOrders":False,
       "sourceDataExported":False,
    }

def host():
    if os.geteuid()!=0 or os.uname().machine!="aarch64":
        raise InvalidSource("EXACT_OWNER_INSTANCE_REQUIRED")
    if not DB.is_file() or DB.is_symlink() or DB.stat().st_mode & 0o077:
        raise InvalidSource("OWNER_PRIVATE_FUTURES_CACHE_MISSING")
    sys.path.insert(0,"/opt/aitradingsystem-paper/infra/ec2-paper")
    import fno_private_bulk_worker as owner
    owner.grant().require("day",owner.today())
    db=sqlite3.connect("file:"+urllib.parse.quote(str(DB),safe="/")+"?mode=ro",
                       uri=True,timeout=15)
    try:
        db.execute("PRAGMA query_only=ON")
        if db.execute("PRAGMA quick_check").fetchone()!=("ok",):
            raise InvalidSource("SQLITE_INTEGRITY_FAILED")
        rows=db.execute("SELECT contract_key,expiry,lot_size,original_http,"
            "original_sha256,first_captured_at,verified_candles "
            "FROM original_futures ORDER BY expiry").fetchall()
        result=replay(rows)
    finally:db.close()
    print(MARKER+json.dumps(result,sort_keys=True,separators=(",",":")))

def runner():
    if not REQUEST.is_file() or REQUEST.is_symlink() or json.loads(
            REQUEST.read_text())!=SOURCE_REQUEST:
        raise InvalidSource("EXACT_OWNER_REQUEST_REQUIRED")
    from verify_ssm_release import verify_aws
    from fno_private_bulk_ops import _aws
    verify_aws()
    packed=base64.b64encode(gzip.compress(Path(__file__).read_bytes(),mtime=0)).decode()
    if not 0<len(packed)<16000 or not re.fullmatch("[A-Za-z0-9+/=]+",packed):
        raise InvalidSource("SCRIPT_BOUND_REJECTED")
    shell=("set -eu\nprintf '%s' '"+packed+"' | base64 -d | gzip -d | "
           "PYTHONDONTWRITEBYTECODE=1 python3 - host\n")
    send=_aws("ssm","send-command","--document-name","AWS-RunShellScript",
              "--instance-ids",INSTANCE,"--timeout-seconds","120",
              "--comment","Owner-only STR176 original HTTP historical aftercost replay",
              "--parameters",json.dumps({"commands":[shell]}))
    cid=(send.get("Command") or {}).get("CommandId")
    if not isinstance(cid,str) or not re.fullmatch("[0-9a-f-]{36}",cid):
        raise InvalidSource("SSM_COMMAND_INVALID")
    for _ in range(24):
        time.sleep(5)
        result=_aws("ssm","get-command-invocation",
                    "--instance-id",INSTANCE,"--command-id",cid)
        if result.get("Status")=="Success":
            raw=result.get("StandardOutputContent","")
            rows=[x[len(MARKER):] for x in raw.splitlines() if x.startswith(MARKER)]
            if len(rows)!=1 or len(rows[0])>1600:
                raise InvalidSource("REPLAY_RECEIPT_UNTRUSTED")
            report=json.loads(rows[0])
            if (report.get("state")!="REAL_SOURCE_SIMULATED_AFTERCOST_NOT_CERTIFIED"
                    or not 1<=report.get("futuresContracts",0)<=12
                    or report.get("independentHistoricalE2ECertified")!=0
                    or report.get("brokerOrders") is not False):
                raise InvalidSource("REPLAY_RECEIPT_UNTRUSTED")
            for field in ("futuresContracts","trainingDecisions","oosDecisions",
                          "signalCandidates","trainingSimulatedTrades",
                          "oosSimulatedTrades","oosNetPaise",
                          "oosMaxDrawdownPaise","invalidSourceWindows"):
                if type(report.get(field)) is not int:
                    raise InvalidSource("REPLAY_COUNTS_INVALID")
            print("STR176_REAL_AFTERCOST:"+json.dumps({
                k:report[k] for k in ("futuresContracts","trainingDecisions",
                "oosDecisions","signalCandidates","trainingSimulatedTrades",
                "oosSimulatedTrades","oosNetPaise","oosMaxDrawdownPaise",
                "invalidSourceWindows")},sort_keys=True,separators=(",",":"))
                +";certified=0/43;brokerOrders=false")
            return
        if result.get("Status") in ("Failed","TimedOut","Cancelled"):
            raise InvalidSource("REAL_HISTORICAL_REPLAY_FAILED")
        if result.get("Status") not in ("Pending","Delayed","InProgress"):
            raise InvalidSource("REPLAY_STATUS_INVALID")
    raise InvalidSource("REPLAY_COMMAND_TIMEOUT")

if __name__=="__main__":
    try:
        if sys.argv[1:]==["host"]:host()
        elif sys.argv[1:]==["runner"]:runner()
        else:raise InvalidSource("INVALID_MODE")
    except (InvalidSource,sqlite3.Error,ValueError,KeyError,TypeError,OSError):
        print("STR176_REAL_AFTERCOST:BLOCKED_VERIFIED_SOURCE_OR_MODEL",file=sys.stderr)
        raise SystemExit(2)
