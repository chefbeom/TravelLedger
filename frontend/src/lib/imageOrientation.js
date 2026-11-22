// EXIF Orientation helpers (no dependencies, usable from node tests).

export function normalizeExifOrientation(value) {
  const numeric = Number(value)
  return Number.isInteger(numeric) && numeric >= 1 && numeric <= 8 ? numeric : 1
}

export function isQuarterTurnOrientation(orientation) {
  return orientation >= 5 && orientation <= 8
}

export function resolveOrientationToApply(exifOrientation, browserAppliesOrientation) {
  return browserAppliesOrientation ? 1 : normalizeExifOrientation(exifOrientation)
}

export function resolveOrientedSize(width, height, orientation) {
  return isQuarterTurnOrientation(orientation) ? { width: height, height: width } : { width, height }
}

// APP1 segment holding only the Orientation tag (little endian TIFF). 36 bytes.
export function buildOrientationExifSegment(orientation) {
  const segment = new Uint8Array(36)
  const view = new DataView(segment.buffer)
  view.setUint16(0, 0xffe1, false)
  view.setUint16(2, 34, false)
  segment.set([0x45, 0x78, 0x69, 0x66, 0x00, 0x00], 4)
  segment.set([0x49, 0x49], 10)
  view.setUint16(12, 42, true)
  view.setUint32(14, 8, true)
  view.setUint16(18, 1, true)
  view.setUint16(20, 0x0112, true)
  view.setUint16(22, 3, true)
  view.setUint32(24, 1, true)
  view.setUint16(28, normalizeExifOrientation(orientation), true)
  view.setUint32(32, 0, true)
  return segment
}

export function insertExifSegment(jpegBytes, segment) {
  if (!jpegBytes || jpegBytes.length < 2 || jpegBytes[0] !== 0xff || jpegBytes[1] !== 0xd8) {
    return null
  }
  const result = new Uint8Array(jpegBytes.length + segment.length)
  result.set(jpegBytes.subarray(0, 2), 0)
  result.set(segment, 2)
  result.set(jpegBytes.subarray(2), 2 + segment.length)
  return result
}

export function drawOrientedImage(context, image, orientation, targetWidth, targetHeight) {
  const requiresSwappedDrawSize = isQuarterTurnOrientation(orientation)
  const drawWidth = requiresSwappedDrawSize ? targetHeight : targetWidth
  const drawHeight = requiresSwappedDrawSize ? targetWidth : targetHeight

  context.save()

  switch (orientation) {
    case 2:
      context.translate(targetWidth, 0)
      context.scale(-1, 1)
      break
    case 3:
      context.translate(targetWidth, targetHeight)
      context.rotate(Math.PI)
      break
    case 4:
      context.translate(0, targetHeight)
      context.scale(1, -1)
      break
    case 5:
      context.transform(0, 1, 1, 0, 0, 0)
      break
    case 6:
      context.transform(0, 1, -1, 0, targetWidth, 0)
      break
    case 7:
      context.transform(0, -1, -1, 0, targetWidth, targetHeight)
      break
    case 8:
      context.transform(0, -1, 1, 0, 0, targetHeight)
      break
    default:
      break
  }

  context.drawImage(image, 0, 0, drawWidth, drawHeight)
  context.restore()
}
