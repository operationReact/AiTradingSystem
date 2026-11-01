from pathlib import Path

path = Path('lib/src/presentation/review/mock_transparency_journey.dart')
text = path.read_text()
old = """        _AuditStep.outcomeAttribution => 'Outcome Attribution',
        _AuditStep.executionQuality => 'Execution Quality',
        _AuditStep.learningRecord => 'Learning Record',
      };
}"""
new = """        _AuditStep.outcomeAttribution => 'Outcome Attribution',
        _AuditStep.executionQuality => 'Execution Quality',
        _AuditStep.improvementAnalysis => 'What Could Improve?',
        _AuditStep.learningRecord => 'Learning Record',
      };
}"""
if new in text:
    raise SystemExit(0)
if old not in text:
    raise SystemExit('missing _stepTitle switch anchor')
text = text.replace(old, new, 1)
path.write_text(text)
