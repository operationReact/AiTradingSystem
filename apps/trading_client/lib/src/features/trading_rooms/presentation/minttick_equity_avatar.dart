import 'package:flutter/material.dart';

/// Company identity shown on all cash-equity rows and cards.
///
/// Only vetted, fixed company-domain mappings may request a favicon. Every other
/// instrument shows a deterministic monogram built from the company name (not the
/// first two letters of the ticker) on a stable per-symbol colour. We never derive
/// an untrusted image URL from an instrument's display name.
class MintTickEquityAvatar extends StatelessWidget {
  const MintTickEquityAvatar({
    required this.tradingSymbol,
    this.companyName,
    this.size = 42,
    super.key,
  });

  final String tradingSymbol;
  final String? companyName;
  final double size;

  /// Official corporate domains for widely held NSE equities (Nifty 100 plus
  /// frequently traded mid caps). Keys are NSE symbols without the -EQ suffix.
  static const Map<String, String> officialDomains = {
    '360ONE': '360.one', '63MOONS': '63moons.com', 'AADHARHFC': 'aadharhousing.com',
    'AARTIIND': 'aarti-industries.com', 'AAVAS': 'aavas.in', 'ABCAPITAL': 'adityabirlacapital.com',
    'ACC': 'acclimited.com', 'ADANIENSOL': 'adanienergysolutions.com',
    'ADANIENT': 'adanienterprises.com', 'ADANIGREEN': 'adanigreenenergy.com',
    'ADANIPORTS': 'adaniports.com', 'ADANIPOWER': 'adanipower.com', 'AMBUJACEM': 'ambujacement.com',
    'ANGELONE': 'angelone.in', 'APOLLOHOSP': 'apollohospitals.com', 'APOLLOTYRE': 'apollotyres.com',
    'ASHOKLEY': 'ashokleyland.com', 'ASIANPAINT': 'asianpaints.com', 'ASTRAL': 'astralltd.com',
    'ATGL': 'adanigas.com', 'AUBANK': 'aubank.in', 'AUROPHARMA': 'aurobindo.com',
    'AXISBANK': 'axisbank.com', 'BAJAJ-AUTO': 'bajajauto.com', 'BAJAJFINSV': 'bajajfinserv.in',
    'BAJFINANCE': 'bajajfinserv.in', 'BANDHANBNK': 'bandhanbank.com', 'BANKBARODA': 'bankofbaroda.in',
    'BEL': 'bel-india.in', 'BERGEPAINT': 'bergerpaints.com', 'BHARATFORG': 'bharatforge.com',
    'BHARTIARTL': 'airtel.in', 'BHEL': 'bhel.com', 'BOSCHLTD': 'bosch.in',
    'BPCL': 'bharatpetroleum.in', 'BRITANNIA': 'britannia.co.in', 'BSE': 'bseindia.com',
    'CANBK': 'canarabank.com', 'CDSL': 'cdslindia.com', 'CHOLAFIN': 'cholamandalam.com',
    'CIPLA': 'cipla.com', 'COALINDIA': 'coalindia.in', 'COFORGE': 'coforge.com',
    'COLPAL': 'colgatepalmolive.co.in', 'CONCOR': 'concorindia.co.in', 'DABUR': 'dabur.com',
    'DIVISLAB': 'divislabs.com', 'DIXON': 'dixoninfo.com', 'DLF': 'dlf.in', 'DMART': 'dmartindia.com',
    'DRREDDY': 'drreddys.com', 'EICHERMOT': 'eicher.in', 'ETERNAL': 'eternal.com',
    'EXIDEIND': 'exideindustries.com', 'FEDERALBNK': 'federalbank.co.in', 'GAIL': 'gailonline.com',
    'GODREJCP': 'godrejcp.com', 'GODREJPROP': 'godrejproperties.com', 'GRASIM': 'grasim.com',
    'HAL': 'hal-india.co.in', 'HAVELLS': 'havells.com', 'HCLTECH': 'hcltech.com',
    'HDFCAMC': 'hdfcfund.com', 'HDFCBANK': 'hdfcbank.com', 'HDFCLIFE': 'hdfclife.com',
    'HEROMOTOCO': 'heromotocorp.com', 'HINDALCO': 'hindalco.com', 'HINDPETRO': 'hindustanpetroleum.com',
    'HINDUNILVR': 'hul.co.in', 'HINDZINC': 'hzlindia.com', 'ICICIBANK': 'icicibank.com',
    'ICICIGI': 'icicilombard.com', 'ICICIPRULI': 'iciciprulife.com', 'IDEA': 'myvi.in',
    'IDFCFIRSTB': 'idfcfirstbank.com', 'IEX': 'iexindia.com', 'INDHOTEL': 'ihcltata.com',
    'INDIGO': 'goindigo.in', 'INDUSINDBK': 'indusind.com', 'INDUSTOWER': 'industowers.com',
    'INFY': 'infosys.com', 'IOC': 'iocl.com', 'IRCTC': 'irctc.co.in', 'IRFC': 'irfc.co.in',
    'ITC': 'itcportal.com', 'JINDALSTEL': 'jindalsteelpower.com', 'JIOFIN': 'jfs.in',
    'JSWENERGY': 'jsw.in', 'JSWSTEEL': 'jsw.in', 'JUBLFOOD': 'jubilantfoodworks.com',
    'KOTAKBANK': 'kotak.com', 'KPITTECH': 'kpit.com', 'LICHSGFIN': 'lichousing.com',
    'LICI': 'licindia.in', 'LODHA': 'lodhagroup.com', 'LT': 'larsentoubro.com',
    'LTIM': 'ltimindtree.com', 'LUPIN': 'lupin.com', 'M&M': 'mahindra.com',
    'M&MFIN': 'mahindrafinance.com', 'MANKIND': 'mankindpharma.com', 'MARICO': 'marico.com',
    'MARUTI': 'marutisuzuki.com', 'MAXHEALTH': 'maxhealthcare.in', 'MAZDOCK': 'mazagondock.in',
    'MCX': 'mcxindia.com', 'MOTHERSON': 'motherson.com', 'MPHASIS': 'mphasis.com',
    'MRF': 'mrftyres.com', 'MUTHOOTFIN': 'muthootfinance.com', 'NAUKRI': 'infoedge.in',
    'NESTLEIND': 'nestle.in', 'NHPC': 'nhpcindia.com', 'NMDC': 'nmdc.co.in', 'NTPC': 'ntpc.co.in',
    'NYKAA': 'nykaa.com', 'OBEROIRLTY': 'oberoirealty.com', 'OIL': 'oil-india.com',
    'ONGC': 'ongcindia.com', 'PAYTM': 'paytm.com', 'PERSISTENT': 'persistent.com',
    'PETRONET': 'petronetlng.in', 'PFC': 'pfcindia.com', 'PHOENIXLTD': 'thephoenixmills.com',
    'PIDILITIND': 'pidilite.com', 'PIIND': 'piindustries.com', 'PNB': 'pnbindia.in',
    'POLICYBZR': 'policybazaar.com', 'POLYCAB': 'polycab.com', 'POWERGRID': 'powergrid.in',
    'PRESTIGE': 'prestigeconstructions.com', 'RECLTD': 'recindia.nic.in', 'RELIANCE': 'ril.com',
    'SAIL': 'sail.co.in', 'SBICARD': 'sbicard.com', 'SBILIFE': 'sbilife.co.in', 'SBIN': 'sbi.co.in',
    'SHREECEM': 'shreecement.com', 'SHRIRAMFIN': 'shriramfinance.in', 'SIEMENS': 'siemens.co.in',
    'SRF': 'srf.com', 'SUNPHARMA': 'sunpharma.com', 'SUPREMEIND': 'supreme.co.in',
    'SUZLON': 'suzlon.com', 'TATACHEM': 'tatachemicals.com', 'TATACOMM': 'tatacommunications.com',
    'TATACONSUM': 'tataconsumer.com', 'TATAELXSI': 'tataelxsi.com', 'TATAMOTORS': 'tatamotors.com',
    'TATAPOWER': 'tatapower.com', 'TATASTEEL': 'tatasteel.com', 'TCS': 'tcs.com',
    'TECHM': 'techmahindra.com', 'TIINDIA': 'tiindia.com', 'TITAN': 'titancompany.in',
    'TMPV': 'tatamotors.com', 'TORNTPHARM': 'torrentpharma.com', 'TORNTPOWER': 'torrentpower.com',
    'TRENT': 'trentlimited.com', 'TVSMOTOR': 'tvsmotor.com', 'ULTRACEMCO': 'ultratechcement.com',
    'UNIONBANK': 'unionbankofindia.co.in', 'UNITDSPR': 'diageoindia.com', 'UPL': 'upl-ltd.com',
    'VBL': 'varunbeverages.com', 'VEDL': 'vedantalimited.com', 'VOLTAS': 'voltas.com',
    'WIPRO': 'wipro.com', 'YESBANK': 'yesbank.in', 'ZYDUSLIFE': 'zyduslife.com',
  };

  static const _noise = {
    'LTD', 'LTD.', 'LIMITED', 'THE', 'OF', 'AND', '&', 'CO', 'CO.', 'COMPANY', 'CORP',
    'CORPORATION', 'PVT', 'PRIVATE', '(INDIA)', 'INDIA', 'INC',
  };

  static String bareSymbol(String tradingSymbol) =>
      tradingSymbol.trim().toUpperCase().replaceFirst(RegExp(r'-EQ$'), '');

  /// Two-letter monogram from the company's significant words, e.g.
  /// "Aadhar Housing Finance Ltd." → "AH", falling back to the ticker.
  static String initials(String tradingSymbol, String? companyName) {
    final words = (companyName ?? '')
        .toUpperCase()
        .split(RegExp(r'\s+'))
        .where((w) => w.isNotEmpty && !_noise.contains(w) && RegExp(r'[A-Z0-9]').hasMatch(w[0]))
        .toList();
    if (words.length >= 2) return '${words[0][0]}${words[1][0]}';
    final symbol = bareSymbol(tradingSymbol).replaceAll(RegExp(r'[^A-Z0-9]'), '');
    if (words.length == 1 && words.first.length >= 2) return words.first.substring(0, 2);
    if (symbol.isEmpty) return 'EQ';
    return symbol.length <= 2 ? symbol : symbol.substring(0, 2);
  }

  static const _hues = [152.0, 200.0, 228.0, 266.0, 300.0, 18.0, 38.0, 176.0];

  static Color monogramColor(String tradingSymbol, Brightness brightness) {
    var hash = 0;
    for (final unit in bareSymbol(tradingSymbol).codeUnits) {
      hash = (hash * 31 + unit) & 0x7fffffff;
    }
    final hue = _hues[hash % _hues.length];
    return HSLColor.fromAHSL(1, hue, 0.55,
        brightness == Brightness.dark ? 0.68 : 0.38).toColor();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final symbol = bareSymbol(tradingSymbol);
    final domain = officialDomains[symbol];
    final label = symbol.isNotEmpty ? symbol : (companyName ?? 'EQ');
    final tint = monogramColor(tradingSymbol, theme.brightness);
    final fallback = Center(
      child: Text(initials(tradingSymbol, companyName),
        style: TextStyle(fontSize: size * 0.34, height: 1,
          fontWeight: FontWeight.w800, color: tint, letterSpacing: -0.2)),
    );
    return Semantics(
      label: '$label company identity',
      child: Container(
        width: size, height: size,
        clipBehavior: Clip.antiAlias,
        decoration: BoxDecoration(
          color: domain == null ? tint.withValues(alpha: 0.13) : Colors.white,
          border: Border.all(color: scheme.outlineVariant.withValues(alpha: 0.6)),
          borderRadius: BorderRadius.circular(size * 0.3),
        ),
        child: domain == null
            ? fallback
            : Padding(
                padding: EdgeInsets.all(size * 0.17),
                child: Image.network(
                  'https://www.google.com/s2/favicons?domain=$domain&sz=128',
                  width: size * 0.66, height: size * 0.66,
                  fit: BoxFit.contain,
                  filterQuality: FilterQuality.medium,
                  errorBuilder: (context, error, stack) => ColoredBox(
                    color: tint.withValues(alpha: 0.13), child: fallback),
                ),
              ),
      ),
    );
  }
}
