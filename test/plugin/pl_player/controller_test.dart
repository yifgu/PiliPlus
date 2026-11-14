import 'dart:async';
import 'dart:typed_data';

import 'package:PiliPlus/pages/video/widgets/player_focus.dart';
import 'package:PiliPlus/plugin/pl_player/controller.dart';
import 'package:PiliPlus/plugin/pl_player/media3_player.dart';
import 'package:PiliPlus/plugin/pl_player/models/data_source.dart';
import 'package:PiliPlus/plugin/pl_player/models/play_status.dart';
import 'package:PiliPlus/utils/storage.dart';
import 'package:PiliPlus/utils/storage_key.dart';
import 'package:PiliPlus/utils/theme_utils.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:hive_ce/hive.dart';
import 'package:material_ui/material_ui.dart';

class _MemoryBox implements Box<dynamic> {
  final data = <dynamic, dynamic>{};

  @override
  dynamic get(dynamic key, {dynamic defaultValue}) => data[key] ?? defaultValue;

  @override
  Future<void> put(dynamic key, dynamic value) async {
    data[key] = value;
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

Map<Object?, Object?> _snapshot({
  int position = 1234,
  int duration = 60000,
  double speed = 1,
  bool completed = false,
}) => {
  'positionMs': position,
  'durationMs': duration,
  'speed': speed,
  'completed': completed,
};

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  const wakeLockChannel =
      'dev.flutter.pigeon.wakelock_plus_platform_interface.WakelockPlusApi.toggle';

  late PlPlayerController controller;
  late StreamController<MethodCall> opens;
  late List<Completer<Map<Object?, Object?>>> results;
  late List<MethodCall> calls;
  late int wakeLockChanges;

  Future<void> sendState({
    int position = 1234,
    int duration = 60000,
    double speed = 1,
    bool playing = true,
    bool buffering = false,
  }) async {
    final response = Completer<void>();
    await messenger.handlePlatformMessage(
      Media3Player.channel.name,
      const StandardMethodCodec().encodeMethodCall(
        MethodCall('state', {
          ..._snapshot(position: position, duration: duration, speed: speed),
          'playing': playing,
          'buffering': buffering,
        }),
      ),
      (data) {
        expect(data, isNotNull);
        expect(const StandardMethodCodec().decodeEnvelope(data!), isNull);
        response.complete();
      },
    );
    await response.future;
  }

  setUpAll(() {
    final setting = _MemoryBox()..data[SettingBoxKey.horizontalScreen] = false;
    GStorage.setting = setting;
    GStorage.video = _MemoryBox();
    GStorage.localCache = _MemoryBox();
    ThemeUtils.themeMode = ThemeMode.light;
    ThemeUtils.lightTheme = ThemeData();
  });

  setUp(() {
    calls = [];
    results = [];
    wakeLockChanges = 0;
    opens = StreamController<MethodCall>.broadcast(sync: true);
    messenger.setMockMethodCallHandler(
      SystemChannels.platform,
      (_) async => null,
    );
    messenger.setMockMessageHandler(wakeLockChannel, (ByteData? message) async {
      wakeLockChanges++;
      return const StandardMessageCodec().encodeMessage(<Object?>[null]);
    });
    messenger.setMockMethodCallHandler(Media3Player.channel, (call) async {
      calls.add(call);
      if (call.method == 'open') {
        final result = Completer<Map<Object?, Object?>>();
        results.add(result);
        opens.add(call);
        return result.future;
      }
      if (call.method == 'close' &&
          results.isNotEmpty &&
          !results.last.isCompleted) {
        results.last.complete(_snapshot());
      }
      return null;
    });
    // Native-first selection intentionally has no mpv controller.
    controller = PlPlayerController.getInstance();
    controller.useMedia3.value = true;
    controller.dataSource = NetworkSource(
      videoSource: '/video.mp4',
      audioSource: null,
    );
  });

  tearDown(() async {
    controller.dispose();
    await Future<void>.delayed(Duration.zero);
    await opens.close();
    messenger.setMockMethodCallHandler(Media3Player.channel, null);
    messenger.setMockMethodCallHandler(SystemChannels.platform, null);
    messenger.setMockMessageHandler(wakeLockChannel, null);
  });

  test(
    'native-first center toggle opens, pauses and resumes without mpv',
    () async {
      expect(controller.videoPlayerController, isNull);
      final opened = opens.stream.first;
      final playback = controller.onDoubleTapCenter();
      await opened;
      await sendState();
      calls.clear();

      await controller.onDoubleTapCenter();
      expect(controller.playerStatus, PlayerStatus.paused);
      await controller.onDoubleTapCenter();
      expect(controller.playerStatus, PlayerStatus.playing);
      expect(calls.map((call) => call.method), ['pause', 'play']);

      await sendState(position: 60000);
      calls.clear();
      await controller.onDoubleTapCenter();
      expect(calls.map((call) => call.method), ['seek', 'play']);
      expect(calls.first.arguments, 0);

      results.single.complete(_snapshot());
      await playback;
    },
  );

  test(
    'native-first completed center replay seeks and opens at zero',
    () async {
      await controller.seekTo(const Duration(seconds: 60));
      controller.updateDuration(const Duration(seconds: 60));
      controller.playerStatus = PlayerStatus.completed;
      final positions = <Duration>[];
      controller.addPositionListener(positions.add);
      final opened = opens.stream.first;

      final playback = controller.onDoubleTapCenter();
      final request = await opened;
      expect(request.arguments['positionMs'], 0);
      expect(controller.currentPosition, Duration.zero);
      expect(positions, [Duration.zero]);
      expect(controller.videoPlayerController, isNull);

      results.single.complete(_snapshot(position: 0));
      await playback;
    },
  );

  test(
    'startup commands wait for native readiness and retain latest intent',
    () async {
      final opened = opens.stream.first;
      final playback = controller.play();
      expect(controller.media3Opening.value, isTrue);
      expect(controller.media3Active.value, isFalse);
      await opened;

      await controller.seekTo(const Duration(seconds: 20));
      await controller.seekTo(const Duration(seconds: 42));
      await controller.setPlaybackSpeed(1.5);
      await controller.setPlaybackSpeed(2);
      await PlPlayerController.pauseIfExists();
      expect(calls.map((call) => call.method), ['open']);
      expect(controller.playerStatus, PlayerStatus.paused);

      await sendState();
      expect(controller.media3Opening.value, isFalse);
      expect(controller.media3Active.value, isTrue);
      expect(controller.currentPosition, const Duration(seconds: 42));
      expect(controller.playbackSpeed, 2);
      expect(controller.playerStatus, PlayerStatus.paused);
      expect(calls.map((call) => call.method), [
        'open',
        'seek',
        'speed',
        'pause',
      ]);
      expect(calls[1].arguments, 42000);
      expect(calls[2].arguments, 2);

      results.single.complete(_snapshot(position: 42000, speed: 2));
      await playback;
    },
  );

  test(
    'a second center toggle during opening pauses instead of reopening',
    () async {
      final opened = opens.stream.first;
      final playback = controller.onDoubleTapCenter();
      await controller.onDoubleTapCenter();
      await opened;
      await sendState();
      expect(calls.where((call) => call.method == 'open'), hasLength(1));
      expect(calls.map((call) => call.method), ['open', 'pause']);

      results.single.complete(_snapshot());
      await playback;
    },
  );

  test('native snapshots notify progress, preserve duration and deduplicate status', () async {
    final positions = <Duration>[];
    final statuses = <PlayerStatus>[];
    controller.addPositionListener(positions.add);
    controller.addStatusLister(statuses.add);
    final opened = opens.stream.first;
    final playback = controller.play();
    await opened;

    await sendState(position: 1234, duration: 60000, speed: 1.5);
    await sendState(position: 1567, duration: 0, speed: 1.5);
    await sendState(position: 2000, playing: false, buffering: true);
    expect(positions, [
      const Duration(milliseconds: 1234),
      const Duration(milliseconds: 1567),
      const Duration(seconds: 2),
    ]);
    expect(statuses, [PlayerStatus.playing, PlayerStatus.paused]);
    expect(controller.durationInMilliseconds, 60000);
    expect(controller.currentPosition, const Duration(seconds: 2));
    expect(controller.isBuffering.value, isTrue);

    results.single.complete(_snapshot(position: 2000));
    await playback;
    expect(controller.isBuffering.value, isFalse);
  });

  test(
    'completion listeners can repeat after the session is cleared',
    () async {
      Future<void>? repeatedPlayback;
      controller.addStatusLister((status) {
        if (status.isCompleted) {
          repeatedPlayback = controller.play(repeat: true);
        }
      });
      final firstOpen = opens.stream.first;
      final firstPlayback = controller.play();
      await firstOpen;
      await sendState();
      final secondOpen = opens.stream.first;
      results.first.complete(_snapshot(position: 60000, completed: true));
      await firstPlayback;

      final repeat = await secondOpen;
      expect(repeat.arguments['positionMs'], 0);
      expect(wakeLockChanges, 1);
      expect(controller.media3Opening.value, isTrue);
      expect(controller.media3Active.value, isFalse);

      results.last.complete(_snapshot(position: 0));
      await repeatedPlayback;
    },
  );

  test(
    'disposing an opening session prevents stale startup commands',
    () async {
      final opened = opens.stream.first;
      final playback = controller.play();
      await opened;
      await controller.seekTo(const Duration(seconds: 42));
      await controller.pause();
      controller.dispose();
      await playback;
      expect(calls.map((call) => call.method), ['open', 'close']);
      expect(controller.media3Opening.value, isFalse);
      expect(controller.media3Active.value, isFalse);
    },
  );

  test(
    'disposing before a prepared repeat cannot open a stale session',
    () async {
      final opened = opens.stream.first;
      final firstPlayback = controller.play();
      await opened;
      results.single.complete(_snapshot());
      await firstPlayback;
      calls.clear();

      final repeat = controller.play(repeat: true);
      controller.dispose();
      await repeat;
      expect(calls.where((call) => call.method == 'open'), isEmpty);
    },
  );

  test(
    'keyboard availability does not imply mpv-only feature support',
    () async {
      final focus = PlayerFocus(
        plPlayerController: controller,
        onSendDanmaku: () {},
        child: const SizedBox.shrink(),
      );
      expect(focus.hasPlayer, isTrue);
      expect(focus.hasMpvPlayer, isFalse);
      await controller.takeScreenshot();
      controller.enterPip();
      controller.enterDesktopPip();
      expect(calls, isEmpty);
      expect(controller.isDesktopPip, isFalse);
    },
  );
}
