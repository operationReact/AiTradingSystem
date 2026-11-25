import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/data/authenticated_api_client.dart';
import 'package:trading_client/src/features/mintick_mind/data/mind_progress_repository.dart';

import 'mind_progress_repository_test.dart' show item;

/// Serves the summary and paged inventory from a fixed list. Other calls fail loudly.
class _PagedApi implements AuthenticatedApiClient {
  _PagedApi(this.summary, this.pages);
  final Map<String, dynamic> summary;
  final List<Map<String, dynamic>> pages;
  final List<Map<String, String?>> requested = [];

  @override
  Future<Object?> getJson(String path,
      {Map<String, String?> query = const {}, Map<String, String> headers = const {},
      Duration timeout = const Duration(seconds: 10)}) async {
    if (path == '/api/customer/mind/v1/progress') return summary;
    if (path == '/api/customer/mind/v1/progress/inventory') {
      requested.add(query);
      final offset = int.parse(query['offset']!);
      return pages.firstWhere((p) => p['offset'] == offset);
    }
    throw StateError('unexpected $path');
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => throw UnimplementedError('${invocation.memberName}');
}

Map<String, dynamic> summaryFor(List<Map<String, dynamic>> items, {int? total, int? done}) {
  final doneCount = done ?? items.where((i) => i['done'] == true).length;
  return {
    'contractVersion': 'mind.progress.v2', 'manifestVersion': 'm1', 'auditedAt': '2026-11-13',
    'generatedAt': '2026-11-13T10:00:00Z', 'backendRevision': 'abc1234', 'revisionKnown': true,
    'sourceIssues': <String>[],
    'paperStage': {'capabilityId': 'engine.x', 'name': 'X', 'status': 'TESTED'},
    'overall': {'total': total ?? items.length, 'done': doneCount,
      'pending': (total ?? items.length) - doneCount, 'blocked': 0, 'percentDone': 0.0},
    'summaries': [
      {'kind': 'ENGINE', 'denominator': items.length, 'done': doneCount, 'tested': 0,
       'inProgress': 0, 'blocked': 0, 'mock': 0, 'notStarted': 0, 'failed': 0},
    ],
    'inventoryPath': '/api/customer/mind/v1/progress/inventory',
  };
}

Map<String, dynamic> page(int offset, List<Map<String, dynamic>> items, int? next) => {
      'contractVersion': 'mind.progress-inventory.v1', 'manifestVersion': 'm1',
      'backendRevision': 'abc1234', 'offset': offset, 'limit': 50, 'total': 3,
      'nextOffset': next, 'items': items,
    };

void main() {
  final a = item('engine.a', status: 'TESTED');
  final b = item('engine.b', status: 'TESTED');
  final c = item('engine.c', status: 'RUNTIME_VERIFIED',
      runtime: {'lastVerifiedAt': '2026-11-13T09:00:00Z', 'verifiedRevision': 'abc1234'});

  test('assembles every page in order and requests bounded pages', () async {
    final api = _PagedApi(summaryFor([a, b, c]), [page(0, [a, b], 2), page(2, [c], null)]);
    final snapshot = await HttpMindProgressRepository(api).load();
    expect(snapshot.items.map((i) => i.id), ['engine.a', 'engine.b', 'engine.c']);
    expect(snapshot.summary('ENGINE')!.done, 1);
    expect(api.requested.every((q) => q['limit'] == '50'), isTrue);
  });

  test('a page that repeats an item fails closed', () async {
    final api = _PagedApi(summaryFor([a, b]), [page(0, [a], 1), page(1, [a], null)]);
    expect(() => HttpMindProgressRepository(api).load(), throwsFormatException);
  });

  test('an incomplete inventory fails closed rather than showing partial progress', () async {
    final api = _PagedApi(summaryFor([a, b, c], total: 3), [page(0, [a, b], null)]);
    expect(() => HttpMindProgressRepository(api).load(), throwsFormatException);
  });

  test('a cursor that does not advance fails closed', () async {
    final api = _PagedApi(summaryFor([a, b]), [page(0, [a], 0), page(0, [b], null)]);
    expect(() => HttpMindProgressRepository(api).load(), throwsFormatException);
  });

  test('a done count that disagrees with the summary fails closed', () async {
    final api = _PagedApi(summaryFor([a, b, c], done: 2), [page(0, [a, b], 2), page(2, [c], null)]);
    expect(() => HttpMindProgressRepository(api).load(), throwsFormatException);
  });

  test('a manifest or revision change mid-load fails closed', () async {
    final changed = page(2, [c], null)..['backendRevision'] = 'other';
    final api = _PagedApi(summaryFor([a, b, c]), [page(0, [a, b], 2), changed]);
    expect(() => HttpMindProgressRepository(api).load(), throwsFormatException);
  });

  test('an unsupported summary contract is rejected', () async {
    final api = _PagedApi(summaryFor([a])..['contractVersion'] = 'mind.progress.v1', [page(0, [a], null)]);
    expect(() => HttpMindProgressRepository(api).load(), throwsFormatException);
  });
}
