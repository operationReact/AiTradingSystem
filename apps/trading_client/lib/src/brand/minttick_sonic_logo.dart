import 'package:audioplayers/audioplayers.dart';

/// Original, locally bundled two-note sound. Not a notification and never
/// part of authentication or market-data readiness. No cloud audio service.
class BrandSonicLogo {
  AudioPlayer? _player;

  Future<void> play() async {
    final player = _player ??= AudioPlayer();
    await player.setAudioContext(
      AudioContextConfig(respectSilence: true).build(),
    );
    await player.stop();
    await player.play(
      AssetSource('audio/minttick-signature.wav'),
      volume: 0.28,
    );
  }

  Future<void> stop() async {
    await _player?.stop();
  }

  Future<void> dispose() async {
    await _player?.dispose();
    _player = null;
  }
}
