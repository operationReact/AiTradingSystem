import 'package:flutter/material.dart';

import '../data/customer_room_repository.dart';
import '../../../data/authenticated_api_client.dart';

/// Small room-level thesis affordance for positional equity rooms.
/// Notes are owner-authored planning text; they do not create signals/orders.
class MintTickRoomThesisShortcut extends StatefulWidget {
  const MintTickRoomThesisShortcut({
    required this.roomId,
    required this.strategy,
    required this.repository,
    super.key,
  });

  final String roomId;
  final String strategy;
  final HttpCustomerRoomRepository? repository;

  @override
  State<MintTickRoomThesisShortcut> createState() =>
      _MintTickRoomThesisShortcutState();
}

class _MintTickRoomThesisShortcutState
    extends State<MintTickRoomThesisShortcut> {
  CustomerRoomThesis? _thesis;
  bool _loading = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final repository = widget.repository;
    if (repository == null) return;
    setState(() { _loading = true; _error = null; });
    try {
      final value = await repository.thesis(widget.roomId);
      if (mounted) setState(() => _thesis = value);
    } catch (_) {
      if (mounted) setState(() => _error = 'Room notes could not be verified.');
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _open() async {
    final repository = widget.repository;
    if (repository == null || _loading) return;
    if (_thesis == null && _error != null) {
      await _load();
      if (!mounted || _thesis == null) return;
    }
    final initial = _thesis ?? CustomerRoomThesis(
      roomId: widget.roomId, content: '', revision: 0, updatedAt: null);
    final saved = await showModalBottomSheet<CustomerRoomThesis>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      backgroundColor: Theme.of(context).colorScheme.surfaceContainerHigh,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(18))),
      builder: (_) => _RoomThesisEditor(
        initial: initial,
        strategy: widget.strategy,
        repository: repository,
      ),
    );
    if (saved != null && mounted) setState(() => _thesis = saved);
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final type = Theme.of(context).textTheme;
    if (widget.repository == null) {
      return Row(children: [
        Icon(Icons.sticky_note_2_outlined, size: 14,
            color: colors.onSurfaceVariant),
        const SizedBox(width: 6),
        Expanded(child: Text('Room notes unavailable',
          key: const ValueKey('room-thesis-unavailable'),
          style: type.labelSmall?.copyWith(color: colors.onSurfaceVariant))),
      ]);
    }
    final label = _loading
        ? 'Loading room notes…'
        : _error ?? (_thesis?.content.trim().isNotEmpty == true
            ? _thesis!.content.trim().replaceAll('\n', ' ')
            : 'Add a room thesis or notes');
    return Row(children: [
      Icon(Icons.sticky_note_2_outlined, size: 14,
          color: colors.onSurfaceVariant),
      const SizedBox(width: 6),
      Expanded(child: Text(label, maxLines: 1, overflow: TextOverflow.ellipsis,
        key: const ValueKey('room-thesis-summary'),
        style: type.labelSmall?.copyWith(color: colors.onSurfaceVariant))),
      TextButton(
        key: const ValueKey('room-thesis-open'),
        onPressed: _loading ? null : _open,
        style: TextButton.styleFrom(
          visualDensity: VisualDensity.compact,
          padding: const EdgeInsets.symmetric(horizontal: 6)),
        child: Text(_thesis?.content.trim().isNotEmpty == true ? 'Edit' : 'Add'),
      ),
      if (_error != null)
        IconButton(
          key: const ValueKey('room-thesis-retry'),
          onPressed: _loading ? null : _load,
          tooltip: 'Retry room notes',
          visualDensity: VisualDensity.compact,
          icon: const Icon(Icons.refresh_rounded, size: 16)),
    ]);
  }
}

class _RoomThesisEditor extends StatefulWidget {
  const _RoomThesisEditor({
    required this.initial,
    required this.strategy,
    required this.repository,
  });

  final CustomerRoomThesis initial;
  final String strategy;
  final HttpCustomerRoomRepository repository;

  @override
  State<_RoomThesisEditor> createState() => _RoomThesisEditorState();
}

class _RoomThesisEditorState extends State<_RoomThesisEditor> {
  late final TextEditingController _content =
      TextEditingController(text: widget.initial.content);
  late int _revision = widget.initial.revision;
  CustomerRoomThesis? _serverVersion;
  bool _saving = false;
  String? _error;

  @override
  void dispose() {
    _content.dispose();
    super.dispose();
  }

  Future<void> _save({int? revision}) async {
    if (_saving) return;
    setState(() { _saving = true; _error = null; });
    try {
      final saved = await widget.repository.saveThesis(
        roomId: widget.initial.roomId,
        content: _content.text,
        expectedRevision: revision ?? _revision,
      );
      if (mounted) Navigator.of(context).pop(saved);
    } catch (error) {
      if (!mounted) return;
      if (error is ApiHttpFailure && error.statusCode == 409) {
        try {
          final latest = await widget.repository.thesis(widget.initial.roomId);
          if (mounted) setState(() {
            _serverVersion = latest;
            _error = 'These notes changed elsewhere. Your draft is still here.';
          });
        } catch (_) {
          if (mounted) setState(() => _error =
              'The save conflicted and the latest notes could not be loaded.');
        }
      } else {
        setState(() => _error = 'Save could not be verified. Review the notes and retry.');
      }
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  void _useLatest() {
    final latest = _serverVersion;
    if (latest == null) return;
    setState(() {
      _content.text = latest.content;
      _revision = latest.revision;
      _serverVersion = null;
      _error = null;
    });
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final type = Theme.of(context).textTheme;
    final bottomInset = MediaQuery.viewInsetsOf(context).bottom;
    return Padding(
      padding: EdgeInsets.fromLTRB(18, 14, 18, bottomInset + 16),
      child: SingleChildScrollView(child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Center(child: Container(width: 34, height: 4,
            decoration: BoxDecoration(color: colors.outlineVariant,
              borderRadius: BorderRadius.circular(99)))),
          const SizedBox(height: 12),
          Text('${widget.strategy} thesis & notes',
            style: type.titleMedium?.copyWith(fontWeight: FontWeight.w800)),
          const SizedBox(height: 4),
          Text('Private notes saved to this room. They do not place or authorize orders.',
            style: type.bodySmall?.copyWith(color: colors.onSurfaceVariant)),
          const SizedBox(height: 12),
          TextField(
            key: const ValueKey('room-thesis-editor'),
            controller: _content,
            autofocus: true,
            maxLength: CustomerRoomThesis.maxContentLength,
            maxLines: 10,
            minLines: 5,
            textCapitalization: TextCapitalization.sentences,
            decoration: const InputDecoration(
              labelText: 'Thesis, entry plan, invalidation or notes',
              hintText: 'Write the reasoning you want to remember for this room.',
              alignLabelWithHint: true,
              border: OutlineInputBorder(),
            ),
          ),
          if (_error != null) ...[
            const SizedBox(height: 4),
            Text(_error!, key: const ValueKey('room-thesis-error'),
              style: type.bodySmall?.copyWith(color: colors.error)),
          ],
          if (_serverVersion != null) ...[
            const SizedBox(height: 8),
            Wrap(spacing: 8, runSpacing: 4, children: [
              OutlinedButton(
                key: const ValueKey('room-thesis-use-latest'),
                onPressed: _saving ? null : _useLatest,
                child: const Text('Load latest'),
              ),
              FilledButton.tonal(
                key: const ValueKey('room-thesis-overwrite-latest'),
                onPressed: _saving ? null : () =>
                    _save(revision: _serverVersion!.revision),
                child: const Text('Overwrite with my draft'),
              ),
            ]),
          ],
          const SizedBox(height: 8),
          Row(children: [
            Expanded(child: OutlinedButton(
              onPressed: _saving ? null : () => Navigator.of(context).pop(),
              child: const Text('Close'),
            )),
            const SizedBox(width: 9),
            Expanded(child: FilledButton(
              key: const ValueKey('room-thesis-save'),
              onPressed: _saving ? null : () => _save(),
              child: Text(_saving ? 'Saving…' : 'Save notes'),
            )),
          ]),
        ],
      )),
    );
  }
}
