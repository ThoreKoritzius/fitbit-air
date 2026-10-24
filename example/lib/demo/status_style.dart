import 'package:flutter/material.dart';

import 'relay_status.dart';

/// Colors and badge for a [StatusLevel], shared by every screen.
class StatusStyle {
  const StatusStyle({
    required this.background,
    required this.foreground,
    this.badge,
  });

  final Color background;
  final Color foreground;

  /// Small corner icon; null when the Air glyph alone says enough.
  final IconData? badge;

  /// Amber that keeps contrast in light and dark themes.
  static Color warningForeground(Brightness brightness) =>
      brightness == Brightness.dark
      ? const Color(0xffffc870)
      : const Color(0xff8a5200);
  static Color warningBackground(Brightness brightness) =>
      brightness == Brightness.dark
      ? const Color(0xff4a3510)
      : const Color(0xfffff1d6);

  factory StatusStyle.of(BuildContext context, StatusLevel level) {
    final colors = Theme.of(context).colorScheme;
    final brightness = Theme.of(context).brightness;
    return switch (level) {
      StatusLevel.active => StatusStyle(
        background: colors.primaryContainer,
        foreground: colors.primary,
      ),
      StatusLevel.quiet => StatusStyle(
        background: colors.secondaryContainer,
        foreground: colors.onSecondaryContainer,
        badge: Icons.bedtime,
      ),
      StatusLevel.warning => StatusStyle(
        background: warningBackground(brightness),
        foreground: warningForeground(brightness),
        badge: Icons.warning_rounded,
      ),
      StatusLevel.problem => StatusStyle(
        background: colors.errorContainer,
        foreground: colors.error,
        badge: Icons.error,
      ),
      StatusLevel.idle || StatusLevel.busy => StatusStyle(
        background: colors.surfaceContainerHighest,
        foreground: colors.onSurfaceVariant,
      ),
    };
  }
}

/// The Air, with a badge for its state: the app's main status symbol.
class StatusEmblem extends StatelessWidget {
  const StatusEmblem({
    super.key,
    required this.level,
    this.size = 88,
    this.paused = false,
  });

  final StatusLevel level;
  final double size;

  /// Shows a pause badge instead of the quiet-hours moon.
  final bool paused;

  @override
  Widget build(BuildContext context) {
    final style = StatusStyle.of(context, level);
    final reduceMotion = MediaQuery.disableAnimationsOf(context);
    final badge = level == StatusLevel.quiet && paused
        ? Icons.pause_circle
        : style.badge;
    final badgeSize = size * .36;
    return Semantics(
      container: true,
      excludeSemantics: true,
      child: SizedBox.square(
        dimension: size,
        child: Stack(
          clipBehavior: Clip.none,
          children: [
            Positioned.fill(
              child: AnimatedContainer(
                duration: reduceMotion
                    ? Duration.zero
                    : const Duration(milliseconds: 220),
                decoration: BoxDecoration(
                  color: style.background,
                  shape: BoxShape.circle,
                ),
                child: Icon(
                  level == StatusLevel.active
                      ? Icons.watch
                      : Icons.watch_outlined,
                  size: size * .46,
                  color: style.foreground,
                ),
              ),
            ),
            if (level == StatusLevel.busy)
              Positioned.fill(
                child: reduceMotion
                    ? DecoratedBox(
                        decoration: BoxDecoration(
                          shape: BoxShape.circle,
                          border: Border.all(
                            color: style.foreground.withValues(alpha: .5),
                            width: 2,
                          ),
                        ),
                      )
                    : CircularProgressIndicator(
                        key: const ValueKey('status-progress'),
                        strokeWidth: 2,
                        color: style.foreground.withValues(alpha: .6),
                      ),
              ),
            if (badge != null)
              Positioned(
                right: -size * .02,
                bottom: -size * .02,
                child: Container(
                  key: ValueKey('status-badge-${level.name}'),
                  padding: EdgeInsets.all(size * .03),
                  decoration: BoxDecoration(
                    color: Theme.of(context).scaffoldBackgroundColor,
                    shape: BoxShape.circle,
                  ),
                  child: Icon(badge, size: badgeSize, color: style.foreground),
                ),
              ),
          ],
        ),
      ),
    );
  }
}
