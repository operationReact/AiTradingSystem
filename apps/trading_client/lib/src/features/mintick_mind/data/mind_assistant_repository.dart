import '../../../data/authenticated_api_client.dart';

/// Read-only grounded answer (contract `mind.assistant.v1`). It never carries a trade action.
class MindAssistantSource {
  const MindAssistantSource({required this.name, required this.endpoint, this.asOf});
  final String name;
  final String endpoint;
  final DateTime? asOf;
}

class MindAssistantAnswer {
  const MindAssistantAnswer({required this.intent, required this.answer, required this.sources,
      required this.generatedAt});
  final String intent;
  final String answer;
  final List<MindAssistantSource> sources;
  final DateTime generatedAt;

  factory MindAssistantAnswer.fromJson(Object? raw) {
    if (raw is! Map<String, dynamic> || raw['contractVersion'] != 'mind.assistant.v1' ||
        raw['readOnly'] != true || raw['liveMoney'] != false) {
      throw const FormatException('Assistant answer must be the explicit read-only contract');
    }
    final intent = raw['intent'], answer = raw['answer'];
    final at = DateTime.tryParse('${raw['generatedAt']}');
    if (intent is! String || answer is! String || at == null) {
      throw const FormatException('Invalid assistant answer');
    }
    return MindAssistantAnswer(
      intent: intent,
      answer: answer,
      generatedAt: at,
      sources: [
        for (final s in (raw['sources'] is List ? raw['sources'] as List : const []))
          if (s is Map<String, dynamic> && s['name'] is String && s['endpoint'] is String)
            MindAssistantSource(name: s['name'] as String, endpoint: s['endpoint'] as String,
                asOf: s['asOf'] is String ? DateTime.tryParse(s['asOf'] as String) : null),
      ],
    );
  }
}

abstract interface class MindAssistantRepository {
  Future<MindAssistantAnswer> ask(String question);
}

class HttpMindAssistantRepository implements MindAssistantRepository {
  HttpMindAssistantRepository(this.api);
  final AuthenticatedApiClient api;

  @override
  Future<MindAssistantAnswer> ask(String question) async {
    final text = question.trim();
    if (text.isEmpty || text.length > 280) throw ArgumentError.value(question, 'question');
    return MindAssistantAnswer.fromJson(
        await api.postJson('/api/customer/mind/v1/assistant/query', body: {'question': text}));
  }
}
