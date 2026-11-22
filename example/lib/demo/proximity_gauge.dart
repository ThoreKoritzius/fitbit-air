import 'dart:math' as math;
import 'package:flutter/material.dart';

/// Signal strength dial; the needle represents RSSI, not a bearing or meters.
class ProximityGauge extends StatelessWidget {
  const ProximityGauge({super.key, required this.strength, required this.rssi});
  final double? strength;
  final int? rssi;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Semantics(
      label: strength == null
          ? 'Searching for Bluetooth signal'
          : 'Bluetooth signal $rssi dBm',
      child: ExcludeSemantics(
        child: TweenAnimationBuilder<double>(
          tween: Tween(end: strength ?? 0),
          duration: MediaQuery.disableAnimationsOf(context)
              ? Duration.zero
              : const Duration(milliseconds: 750),
          curve: Curves.easeInOutCubic,
          builder: (context, value, _) => AspectRatio(
            aspectRatio: 1.15,
            child: Stack(
              children: [
                Positioned.fill(
                  child: CustomPaint(
                    painter: _GaugePainter(
                      value: value,
                      active: strength != null,
                      colors: colors,
                    ),
                  ),
                ),
                Positioned(
                  left: 0,
                  right: 0,
                  bottom: 8,
                  child: Column(
                    children: [
                      Text(
                        rssi == null ? '—' : '$rssi',
                        style: Theme.of(context).textTheme.headlineMedium
                            ?.copyWith(
                              fontWeight: FontWeight.w600,
                              letterSpacing: -1,
                            ),
                      ),
                      Text(
                        rssi == null
                            ? 'Waiting for signal'
                            : 'dBm · signal strength',
                        style: Theme.of(context).textTheme.labelSmall?.copyWith(
                          color: colors.onSurfaceVariant,
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _GaugePainter extends CustomPainter {
  const _GaugePainter({
    required this.value,
    required this.active,
    required this.colors,
  });
  final double value;
  final bool active;
  final ColorScheme colors;
  static const start = math.pi * .75;
  static const sweep = math.pi * 1.5;
  Color _color(double t) => t < .5
      ? Color.lerp(const Color(0xff549dda), const Color(0xff51b4a4), t * 2)!
      : Color.lerp(
          const Color(0xff51b4a4),
          const Color(0xffefb35b),
          (t - .5) * 2,
        )!;

  @override
  void paint(Canvas canvas, Size size) {
    final center = Offset(size.width / 2, size.height * .51);
    final radius = size.width * .41;
    final arc = Rect.fromCircle(center: center, radius: radius);
    final paint = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = 11;
    for (var i = 0; i < 100; i++) {
      paint.color = active ? _color(i / 99) : colors.outlineVariant;
      canvas.drawArc(
        arc,
        start + sweep * i / 100,
        sweep / 100 + .003,
        false,
        paint,
      );
    }
    for (var i = 0; i <= 10; i++) {
      final angle = start + sweep * i / 10;
      final unit = Offset(math.cos(angle), math.sin(angle));
      paint
        ..strokeWidth = i % 5 == 0 ? 2 : 1
        ..color = colors.onSurfaceVariant.withValues(alpha: .45);
      canvas.drawLine(
        center + unit * (radius - 15),
        center + unit * (radius - (i % 5 == 0 ? 25 : 20)),
        paint,
      );
    }
    if (active) {
      final angle = start + sweep * value.clamp(0, 1);
      final direction = Offset(math.cos(angle), math.sin(angle));
      final perpendicular = Offset(-direction.dy, direction.dx);
      final needle = Path()
        ..moveTo(
          (center + direction * (radius - 32)).dx,
          (center + direction * (radius - 32)).dy,
        )
        ..lineTo(
          (center + perpendicular * 5 - direction * 12).dx,
          (center + perpendicular * 5 - direction * 12).dy,
        )
        ..lineTo(
          (center - perpendicular * 5 - direction * 12).dx,
          (center - perpendicular * 5 - direction * 12).dy,
        )
        ..close();
      canvas.drawShadow(needle, Colors.black.withValues(alpha: .2), 3, false);
      canvas.drawPath(needle, Paint()..color = colors.onSurface);
      canvas.drawCircle(center, 9, Paint()..color = colors.surface);
      canvas.drawCircle(center, 5, Paint()..color = _color(value));
    } else {
      canvas.drawCircle(center, 6, Paint()..color = colors.outline);
    }
  }

  @override
  bool shouldRepaint(_GaugePainter old) =>
      old.value != value || old.active != active || old.colors != colors;
}

class SignalHistory extends StatelessWidget {
  const SignalHistory({super.key, required this.values});
  final List<double> values;
  @override
  Widget build(BuildContext context) => SizedBox(
    height: 36,
    width: double.infinity,
    child: CustomPaint(
      painter: _HistoryPainter(List.of(values), Theme.of(context).colorScheme),
    ),
  );
}

class _HistoryPainter extends CustomPainter {
  const _HistoryPainter(this.values, this.colors);
  final List<double> values;
  final ColorScheme colors;
  @override
  void paint(Canvas canvas, Size size) {
    if (values.length < 2) return;
    final path = Path();
    for (var i = 0; i < values.length; i++) {
      final x = size.width * i / (values.length - 1);
      final y = size.height * (1 - ((values[i] + 100) / 60).clamp(0, 1));
      if (i == 0) {
        path.moveTo(x, y);
      } else {
        path.lineTo(x, y);
      }
    }
    canvas.drawPath(
      path,
      Paint()
        ..color = colors.primary
        ..style = PaintingStyle.stroke
        ..strokeWidth = 2
        ..strokeJoin = StrokeJoin.round,
    );
  }

  @override
  bool shouldRepaint(_HistoryPainter old) => true;
}
