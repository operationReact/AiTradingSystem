import 'package:flutter/material.dart';

import '../../../data/authenticated_api_client.dart';

/// Same compact rename experience in the Rooms list and inside each room.
/// The server is authoritative; a failing save leaves the dialog open.
class MintTickRoomRenameDialog extends StatefulWidget {
  const MintTickRoomRenameDialog({
    required this.currentName,
    required this.onSave,
    super.key,
  });

  final String currentName;
  final Future<void> Function(String) onSave;

  @override
  State<MintTickRoomRenameDialog> createState() =>
      _MintTickRoomRenameDialogState();
}

class _MintTickRoomRenameDialogState extends State<MintTickRoomRenameDialog> {
  late final TextEditingController _name;
  bool _saving = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _name = TextEditingController(text: widget.currentName);
  }

  @override
  void dispose() {
    _name.dispose();
    super.dispose();
  }

  String get _normalized =>
      _name.text.trim().replaceAll(RegExp(r'\s+'), ' ');

  bool get _valid {
    final value = _normalized;
    return value.isNotEmpty && value.length <= 80 &&
        value.runes.every((point) => point >= 32 && point != 127) &&
        value != widget.currentName;
  }

  Future<void> _save() async {
    if (_saving || !_valid) return;
    final requested = _normalized;
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      await widget.onSave(requested);
      if (mounted) Navigator.of(context).pop();
    } on ApiHttpFailure catch (failure) {
      if (!mounted) return;
      setState(() => _error = switch (failure.statusCode) {
        409 => 'Another room already uses this name. Choose a different name.',
        404 => 'This room no longer exists. Refresh Trading Rooms.',
        401 || 403 => 'Your session expired. Sign in and retry.',
        _ => 'Could not save room name (HTTP ${failure.statusCode}). Retry.',
      });
    } catch (_) {
      if (!mounted) return;
      setState(() => _error =
          'Could not verify the new name. Refresh your rooms before retrying.');
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final color = Theme.of(context).colorScheme;
    return AlertDialog(
      title: const Text('Rename room'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text('Change the display name without affecting '
              'the room balance, positions or orders.'),
          const SizedBox(height: 12),
          TextField(
            key: const ValueKey('room-rename-input'),
            controller: _name,
            autofocus: true,
            maxLength: 80,
            enabled: !_saving,
            textCapitalization: TextCapitalization.sentences,
            textInputAction: TextInputAction.done,
            onSubmitted: (_) => _save(),
            onChanged: (_) => setState(() => _error = null),
            decoration: const InputDecoration(
              isDense: true,
              labelText: 'Room name',
              hintText: 'Enter a room name',
            ),
          ),
          if (_error != null)
            Text(_error!,
              key: const ValueKey('room-rename-error'),
              style: Theme.of(context).textTheme.bodySmall
                  ?.copyWith(color: color.error)),
          if (_saving) const LinearProgressIndicator(minHeight: 2),
        ],
      ),
      actions: [
        TextButton(
          onPressed: _saving ? null : () => Navigator.of(context).pop(),
          child: const Text('Cancel')),
        FilledButton(
          key: const ValueKey('room-rename-confirm'),
          onPressed: _saving || !_valid ? null : _save,
          child: const Text('Save name')),
      ],
    );
  }
}
