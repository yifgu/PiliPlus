import 'package:material_ui/material_ui.dart';

class Media3PlaybackPlaceholder extends StatelessWidget {
  const Media3PlaybackPlaceholder({
    super.key,
    required this.onPlay,
    required this.onOpenSettings,
    required this.onBack,
  });

  final VoidCallback onPlay;
  final VoidCallback onOpenSettings;
  final VoidCallback onBack;

  @override
  Widget build(BuildContext context) => Stack(
    children: [
      Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Text(
              'Media3 · Dolby Vision Profile 5',
              style: TextStyle(color: Colors.white),
            ),
            const SizedBox(height: 8),
            FilledButton.icon(
              onPressed: onPlay,
              icon: const Icon(Icons.play_arrow_rounded),
              label: const Text('播放'),
            ),
            const Padding(
              padding: EdgeInsets.symmetric(horizontal: 24),
              child: Text(
                '播放失败时，请在音视频设置中切回软解并重新打开视频',
                textAlign: TextAlign.center,
                style: TextStyle(color: Colors.white70, fontSize: 12),
              ),
            ),
            TextButton(onPressed: onOpenSettings, child: const Text('音视频设置')),
          ],
        ),
      ),
      Align(
        alignment: Alignment.topLeft,
        child: IconButton(
          tooltip: '返回',
          onPressed: onBack,
          icon: const Icon(Icons.arrow_back, color: Colors.white),
        ),
      ),
    ],
  );
}
