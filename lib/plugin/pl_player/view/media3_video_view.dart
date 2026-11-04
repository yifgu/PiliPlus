import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';

class Media3VideoView extends StatelessWidget {
  const Media3VideoView({super.key});

  static const viewType = 'com.example.piliplus/media3_view';

  @override
  Widget build(BuildContext context) => PlatformViewLink(
    viewType: viewType,
    surfaceFactory: (context, controller) => AndroidViewSurface(
      controller: controller as AndroidViewController,
      hitTestBehavior: PlatformViewHitTestBehavior.opaque,
      gestureRecognizers: {
        Factory<OneSequenceGestureRecognizer>(EagerGestureRecognizer.new),
      },
    ),
    onCreatePlatformView: (params) {
      // Hybrid composition preserves the native SurfaceView's HDR output.
      return PlatformViewsService.initExpensiveAndroidView(
          id: params.id,
          viewType: viewType,
          layoutDirection: Directionality.of(context),
          creationParamsCodec: const StandardMessageCodec(),
          onFocus: () => params.onFocusChanged(true),
        )
        ..addOnPlatformViewCreatedListener(params.onPlatformViewCreated)
        ..create();
    },
  );
}
