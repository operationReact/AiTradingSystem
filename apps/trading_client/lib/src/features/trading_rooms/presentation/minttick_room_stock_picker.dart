import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../../../data/authenticated_api_client.dart';
import '../../../data/instrument_repository.dart';
import '../data/customer_market_repository.dart';

/// Shared picker used by both the room chart and the order ticket.
/// Results are authenticated, backend-classified NSE/BSE cash equities.
class MintTickRoomStockPicker extends StatefulWidget {
  const MintTickRoomStockPicker({
    required this.market,
    this.initialQuery = '',
    super.key,
  });

  final HttpCustomerMarketRepository market;
  final String initialQuery;

  /// The customer route returns cash equities only; keep its exchange and
  /// segment constraints so unexpected provider rows cannot reach an order.
  static bool cashEquity(InstrumentRecord row) {
    if (row.id <= 0 || row.tradingSymbol.isEmpty ||
        (row.exchange != 'NSE' && row.exchange != 'BSE')) {
      return false;
    }
    final segment = row.exchangeSegment.toUpperCase();
    final symbol = row.tradingSymbol.toUpperCase();
    final type = row.instrumentType.toUpperCase();
    return (row.exchange == 'NSE' && segment == 'NSE_CM' &&
            symbol.endsWith('-EQ')) ||
        (row.exchange == 'BSE' && segment == 'BSE_CM' &&
            (type == 'EQ' || symbol.endsWith('-EQ')));
  }

  @override
  State<MintTickRoomStockPicker> createState() => _MintTickRoomStockPickerState();
}

class _MintTickRoomStockPickerState extends State<MintTickRoomStockPicker> {
  late final TextEditingController _query;
  Timer? _debounce;
  int _generation = 0;
  bool _loading = false;
  bool _requested = false;
  String? _error;
  List<InstrumentRecord> _results = const [];
  int _total = 0;

  @override
  void initState() {
    super.initState();
    _query = TextEditingController(text: widget.initialQuery.trim());
    if (_query.text.length >= 2) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) _search();
      });
    }
  }

  @override
  void dispose() {
    _generation++;
    _debounce?.cancel();
    _query.dispose();
    super.dispose();
  }

  void _changed(String value) {
    _debounce?.cancel();
    ++_generation;
    setState(() {
      _loading = false;
      _requested = false;
      _results = const [];
      _total = 0;
      _error = null;
    });
    if (value.trim().length >= 2) {
      _debounce = Timer(const Duration(milliseconds: 280), _search);
    }
  }

  Future<void> _search() async {
    _debounce?.cancel();
    final value = _query.text.trim();
    if (value.length < 2) return;
    final request = ++_generation;
    setState(() {
      _loading = true;
      _requested = true;
      _error = null;
    });
    try {
      // Backend endpoint enforces owner authentication and cash-equity
      // classification. No operator/unauthenticated route is used.
      final page = await widget.market.searchPage(query: value, page: 0);
      if (!mounted || request != _generation) return;
      final rows = page.content.where(MintTickRoomStockPicker.cashEquity).toList();
      setState(() {
        _results = rows;
        _total = page.totalElements;
        if (page.catalogueVersion == null || page.activatedAt == null) {
          _error = 'Stock catalogue is not active. Try again after the '
              'market catalogue has been restored.';
        } else if (rows.isEmpty && page.totalElements > 0) {
          _error = 'No supported NSE/BSE cash equities in these results. '
              'Try a more precise symbol.';
        }
      });
    } on ApiAuthenticationRequired {
      if (mounted && request == _generation) {
        setState(() => _error =
            'Sign in to MintTick again to search stocks.');
      }
    } on ApiHttpFailure catch (failure) {
      if (mounted && request == _generation) {
        setState(() => _error =
            failure.statusCode == 401 || failure.statusCode == 403
              ? 'Your session has expired. Sign in and retry.'
              : 'Stock catalogue request failed (HTTP ${failure.statusCode}). '
                'Check the backend and retry.');
      }
    } catch (_) {
      if (mounted && request == _generation) {
        setState(() => _error =
            'Stock search is unavailable. Check your connection and retry.');
      }
    } finally {
      if (mounted && request == _generation) {
        setState(() => _loading = false);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final keyboard = MediaQuery.viewInsetsOf(context).bottom;
    final height = math.max(
      190.0,
      math.min(460.0, MediaQuery.sizeOf(context).height - keyboard - 90),
    );
    return SafeArea(
      top: false,
      child: Padding(
        padding: EdgeInsets.fromLTRB(15, 14, 15, keyboard + 10),
        child: SizedBox(
          height: height,
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [
              Expanded(child: Text('Select NSE / BSE stock',
                style: text.titleMedium?.copyWith(fontWeight: FontWeight.w800))),
              IconButton(
                tooltip: 'Close stock picker',
                onPressed: () => Navigator.pop(context),
                icon: const Icon(Icons.close_rounded, size: 20)),
            ]),
            const SizedBox(height: 8),
            TextField(
              controller: _query,
              key: const ValueKey('room-symbol-search'),
              autofocus: true,
              textInputAction: TextInputAction.search,
              onChanged: _changed,
              onSubmitted: (_) => _search(),
              decoration: InputDecoration(
                hintText: 'Search RELIANCE, SBIN, HDFC...',
                isDense: true,
                prefixIcon: const Icon(Icons.search_rounded, size: 18),
                suffixIcon: IconButton(
                  key: const ValueKey('room-symbol-search-submit'),
                  tooltip: 'Search stocks',
                  onPressed: _loading || _query.text.trim().length < 2
                    ? null : _search,
                  icon: const Icon(Icons.arrow_forward_rounded, size: 18)),
              ),
            ),
            const SizedBox(height: 8),
            if (_loading)
              const LinearProgressIndicator(minHeight: 2),
            if (_error != null)
              Padding(padding: const EdgeInsets.symmetric(vertical: 7),
                child: Row(children: [
                  Expanded(child: Text(_error!,
                    key: const ValueKey('room-stock-search-error'),
                    style: text.bodySmall?.copyWith(color: scheme.error))),
                  TextButton(onPressed: _loading ? null : _search,
                    child: const Text('Retry')),
                ])),
            if (!_loading && _error == null)
              Padding(padding: const EdgeInsets.only(bottom: 7),
                child: Text(
                  _requested && _results.isEmpty
                    ? 'No matching cash equities. Try the full company name.'
                    : _requested
                      ? '${_results.length} matching stocks'
                      : 'Type at least 2 characters to search the active catalogue.',
                  key: const ValueKey('room-stock-search-status'),
                  style: text.bodySmall?.copyWith(
                    color: scheme.onSurfaceVariant)),
              ),
            Expanded(child: ListView.separated(
              keyboardDismissBehavior: ScrollViewKeyboardDismissBehavior.onDrag,
              itemCount: _results.length,
              separatorBuilder: (_, _) =>
                Divider(height: 1, color: scheme.outlineVariant),
              itemBuilder: (context, index) {
                final stock = _results[index];
                return ListTile(
                  key: ValueKey('room-stock-result-${stock.id}'),
                  dense: true,
                  contentPadding: const EdgeInsets.symmetric(horizontal: 4),
                  title: Text(stock.tradingSymbol,
                    maxLines: 1, overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontWeight: FontWeight.w700)),
                  subtitle: Text(stock.name.isEmpty
                    ? '${stock.exchange} · Equity'
                    : '${stock.name} · ${stock.exchange}',
                    maxLines: 1, overflow: TextOverflow.ellipsis),
                  trailing: const Icon(Icons.chevron_right_rounded, size: 19),
                  onTap: () => Navigator.of(context).pop(stock),
                );
              },
            )),
            if (_total > _results.length && _results.isNotEmpty)
              Text('Showing the first ${_results.length} of $_total. '
                'Refine your search to find other symbols.',
                style: text.labelSmall?.copyWith(
                  color: scheme.onSurfaceVariant)),
          ]),
        ),
      ),
    );
  }
}
