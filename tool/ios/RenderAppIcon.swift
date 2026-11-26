import CoreGraphics
import Foundation
import ImageIO

guard CommandLine.arguments.count == 3 else {
    fatalError("Usage: RenderAppIcon.swift source.png output.png")
}

let source = URL(fileURLWithPath: CommandLine.arguments[1])
let output = URL(fileURLWithPath: CommandLine.arguments[2])
let bounds = CGRect(x: 0, y: 0, width: 1024, height: 1024)
let color = CGColorSpace(name: CGColorSpace.sRGB)!

guard let file = CGImageSourceCreateWithURL(source as CFURL, nil),
      let image = CGImageSourceCreateImageAtIndex(file, 0, nil),
      let canvas = CGContext(
          data: nil, width: 1024, height: 1024, bitsPerComponent: 8,
          bytesPerRow: 0, space: color,
          bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
      ) else { fatalError("Could not read the shared app icon") }

canvas.setFillColor(red: 18 / 255.0, green: 60 / 255.0, blue: 54 / 255.0, alpha: 1)
canvas.fill(bounds)
canvas.interpolationQuality = .high
canvas.draw(image, in: bounds)

guard let rendered = canvas.makeImage(),
      let result = CGImageDestinationCreateWithURL(output as CFURL, "public.png" as CFString, 1, nil)
else { fatalError("Could not render the iOS app icon") }

CGImageDestinationAddImage(result, rendered, nil)
guard CGImageDestinationFinalize(result) else { fatalError("Could not write the iOS app icon") }
