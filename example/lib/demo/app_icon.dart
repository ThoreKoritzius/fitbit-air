import 'dart:convert';

import 'package:flutter/material.dart';

/// Retains the image provider across status polls, avoiding a new decode/frame.
class AppIcon extends StatefulWidget {
  const AppIcon({super.key, required this.encoded});

  final String? encoded;

  @override
  State<AppIcon> createState() => _AppIconState();
}

class _AppIconState extends State<AppIcon> {
  MemoryImage? _image;

  @override
  void initState() {
    super.initState();
    _decode();
  }

  @override
  void didUpdateWidget(covariant AppIcon oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.encoded != widget.encoded) _decode();
  }

  void _decode() {
    try {
      _image = widget.encoded == null
          ? null
          : MemoryImage(base64Decode(widget.encoded!));
    } on FormatException {
      _image = null;
    }
  }

  @override
  Widget build(BuildContext context) => SizedBox.square(
    dimension: 32,
    child: _image == null
        ? const Icon(Icons.apps)
        : Image(
            image: _image!,
            gaplessPlayback: true,
            excludeFromSemantics: true,
            errorBuilder: (context, error, stackTrace) =>
                const Icon(Icons.apps),
          ),
  );
}
