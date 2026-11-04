import 'package:PiliPlus/plugin/pl_player/view/media3_video_view.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  testWidgets(
    'native player shares portrait layout with interactive comments',
    (tester) async {
      debugDefaultTargetPlatformOverride = TargetPlatform.android;
      tester.view.physicalSize = const Size(400, 800);
      tester.view.devicePixelRatio = 1;
      final calls = <MethodCall>[];
      final messenger = tester.binding.defaultBinaryMessenger;
      messenger.setMockMethodCallHandler(SystemChannels.platform_views, (
        call,
      ) async {
        calls.add(call);
        return null;
      });
      addTearDown(() {
        messenger.setMockMethodCallHandler(SystemChannels.platform_views, null);
        debugDefaultTargetPlatformOverride = null;
        tester.view.resetPhysicalSize();
        tester.view.resetDevicePixelRatio();
      });
      var selected = -1;
      Widget page(double height) => MaterialApp(
        home: Scaffold(
          body: Column(
            children: [
              SizedBox(
                height: height,
                width: double.infinity,
                child: const Media3VideoView(),
              ),
              Expanded(
                child: ListView.builder(
                  itemCount: 100,
                  itemBuilder: (context, index) => TextButton(
                    onPressed: () => selected = index,
                    child: Text('Comment $index'),
                  ),
                ),
              ),
            ],
          ),
        ),
      );

      await tester.pumpWidget(page(225));
      await tester.pump();
      final player = find.byType(Media3VideoView);
      expect(tester.getRect(player), const Rect.fromLTWH(0, 0, 400, 225));
      expect(
        tester.getTopLeft(find.text('Comment 0')).dy,
        greaterThanOrEqualTo(225),
      );
      final create = calls.where((call) => call.method == 'create').single;
      expect(create.arguments['viewType'], Media3VideoView.viewType);
      expect(create.arguments['hybrid'], isTrue);

      await tester.tap(find.text('Comment 0'));
      expect(selected, 0);
      await tester.drag(find.byType(ListView), const Offset(0, -300));
      await tester.pumpAndSettle();
      expect(find.text('Comment 0').hitTestable(), findsNothing);
      expect(tester.getSize(player), const Size(400, 225));

      tester.view.physicalSize = const Size(800, 400);
      await tester.pumpWidget(page(400));
      await tester.pump();
      expect(tester.getSize(player), const Size(800, 400));
      expect(calls.where((call) => call.method == 'create'), hasLength(1));
      expect(calls.where((call) => call.method == 'dispose'), isEmpty);
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump();
      expect(calls.where((call) => call.method == 'dispose'), hasLength(1));
    },
  );
}
