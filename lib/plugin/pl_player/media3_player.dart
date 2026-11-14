import 'package:flutter/services.dart';

class Media3PlaybackResult {
  const Media3PlaybackResult({
    required this.position,
    required this.duration,
    required this.speed,
    required this.completed,
    this.error,
  });

  factory Media3PlaybackResult.fromMap(Map<Object?, Object?> value) {
    if (value
        case {
          'positionMs': int position,
          'durationMs': int duration,
          'speed': num speed,
          'completed': bool completed,
        }
        when position >= 0 && duration >= 0 && speed.isFinite && speed > 0) {
      return Media3PlaybackResult(
        position: Duration(milliseconds: position),
        duration: Duration(milliseconds: duration),
        speed: speed.toDouble(),
        completed: completed,
        error: value['error'] as String?,
      );
    }
    throw const FormatException('Invalid Media3 playback result');
  }

  final Duration position;
  final Duration duration;
  final double speed;
  final bool completed;
  final String? error;
}

class Media3Player {
  static const channel = MethodChannel('com.example.piliplus/media3');
  static Object? _activeSession;

  /// Completes when the native session closes, not when playback becomes ready.
  static Future<Media3PlaybackResult> open({
    required String video,
    String? audio,
    required Duration position,
    required double speed,
    required Map<String, String> headers,
    String title = '',
    List<double> speeds = const [0.5, 0.75, 1, 1.25, 1.5, 1.75, 2, 3],
    Duration seekStep = const Duration(seconds: 10),
    Duration controlsTimeout = const Duration(seconds: 3),
    bool quickDoubleTap = true,
    double longPressSpeed = 3,
    int accentColor = 0xFFFFFFFF,
    void Function(Media3PlaybackResult state, bool playing, bool buffering)?
    onState,
    Future<void> Function()? onFullscreen,
    Future<void> Function()? onBack,
    void Function(bool locked)? onLock,
  }) async {
    if (_activeSession != null) {
      throw PlatformException(code: 'busy', message: 'Media3 is already open');
    }
    final session = Object();
    _activeSession = session;
    try {
      channel.setMethodCallHandler((call) async {
        switch (call.method) {
          case 'state':
            final state = Map<Object?, Object?>.from(call.arguments as Map);
            onState?.call(
              Media3PlaybackResult.fromMap(state),
              state['playing'] as bool,
              state['buffering'] as bool,
            );
          case 'fullscreen':
            await onFullscreen?.call();
          case 'back':
            await onBack?.call();
          case 'lock':
            onLock?.call(call.arguments as bool);
          default:
            throw MissingPluginException(
              'Unknown Media3 event: ${call.method}',
            );
        }
      });
      final result = await channel.invokeMapMethod<Object?, Object?>('open', {
        'video': video,
        if (audio != null && audio.isNotEmpty) 'audio': audio,
        'positionMs': position.inMilliseconds,
        'speed': speed,
        'headers': headers,
        'title': title,
        'speeds': speeds,
        'seekStepMs': seekStep.inMilliseconds,
        'controlsTimeoutMs': controlsTimeout.inMilliseconds,
        'quickDoubleTap': quickDoubleTap,
        'longPressSpeed': longPressSpeed,
        'accentColor': accentColor,
      });
      if (result == null) {
        throw const FormatException('Missing Media3 playback result');
      }
      return Media3PlaybackResult.fromMap(result);
    } finally {
      if (identical(_activeSession, session)) {
        channel.setMethodCallHandler(null);
        _activeSession = null;
      }
    }
  }

  static Future<void> close() => channel.invokeMethod<void>('close');
  static Future<void> pause() => channel.invokeMethod<void>('pause');
  static Future<void> play() => channel.invokeMethod<void>('play');
  static Future<void> seek(Duration position) =>
      channel.invokeMethod<void>('seek', position.inMilliseconds);
  static Future<void> setSpeed(double speed) =>
      channel.invokeMethod<void>('speed', speed);
  static Future<void> setLocked(bool locked) =>
      channel.invokeMethod<void>('lock', locked);
}
