import 'dart:async';
import 'package:flutter/material.dart';
import 'demo_controller.dart';

/// Stable geometry and delayed progress avoid flashes on very fast operations.
class RelayToggleButton extends StatefulWidget {
  const RelayToggleButton({
    super.key,
    required this.running,
    required this.transition,
    required this.onPressed,
  });
  final bool running;
  final RelayTransition? transition;
  final VoidCallback? onPressed;

  @override
  State<RelayToggleButton> createState() => _RelayToggleButtonState();
}

class _RelayToggleButtonState extends State<RelayToggleButton> {
  Timer? _progressTimer;
  bool _showProgress = false;

  @override
  void initState() {
    super.initState();
    _scheduleProgress();
  }

  @override
  void didUpdateWidget(covariant RelayToggleButton oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.transition != widget.transition) _scheduleProgress();
  }

  void _scheduleProgress() {
    _progressTimer?.cancel();
    _showProgress = false;
    if (widget.transition != null) {
      _progressTimer = Timer(const Duration(milliseconds: 120), () {
        if (mounted) setState(() => _showProgress = true);
      });
    }
  }

  @override
  void dispose() {
    _progressTimer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final reducedMotion = MediaQuery.disableAnimationsOf(context);
    final background = widget.running
        ? colors.surfaceContainerHighest
        : colors.primary;
    final foreground = widget.running ? colors.onSurface : colors.onPrimary;
    final label = _showProgress
        ? switch (widget.transition) {
            RelayTransition.starting => 'Starting…',
            RelayTransition.stopping => 'Stopping…',
            null =>
              widget.running ? 'Stop notifications' : 'Start notifications',
          }
        : widget.running
        ? 'Stop notifications'
        : 'Start notifications';
    return Semantics(
      liveRegion: true,
      child: FilledButton(
        key: const ValueKey('relay-toggle'),
        style: FilledButton.styleFrom(
          backgroundColor: background,
          foregroundColor: foreground,
          disabledBackgroundColor: background,
          disabledForegroundColor: foreground,
          animationDuration: reducedMotion
              ? Duration.zero
              : const Duration(milliseconds: 180),
        ),
        onPressed: widget.transition == null ? widget.onPressed : null,
        child: Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            SizedBox.square(
              dimension: 18,
              child: _showProgress
                  ? reducedMotion
                        ? Icon(
                            Icons.hourglass_top,
                            key: const ValueKey('relay-toggle-progress'),
                            size: 18,
                            color: foreground,
                          )
                        : CircularProgressIndicator(
                            key: const ValueKey('relay-toggle-progress'),
                            strokeWidth: 2,
                            color: foreground,
                          )
                  : Icon(
                      widget.running ? Icons.stop : Icons.play_arrow,
                      size: 18,
                    ),
            ),
            const SizedBox(width: 8),
            Flexible(
              child: Stack(
                alignment: Alignment.center,
                children: [
                  const ExcludeSemantics(
                    child: Opacity(
                      opacity: 0,
                      child: Text(
                        'Start notifications',
                        textAlign: TextAlign.center,
                      ),
                    ),
                  ),
                  Text(label, textAlign: TextAlign.center),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
