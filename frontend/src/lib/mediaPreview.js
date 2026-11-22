import exifr from 'exifr'
import {
  buildOrientationExifSegment,
  drawOrientedImage,
  insertExifSegment,
  normalizeExifOrientation,
  resolveOrientationToApply,
  resolveOrientedSize,
} from './imageOrientation.js'

export const THUMBNAIL_CACHE_VERSION = '2'

export const THUMBNAIL_VARIANTS = Object.freeze({
  pin: 'pin',
  mini: 'mini',
  preview: 'preview',
  detail: 'detail',
})

export const THUMBNAIL_WIDTHS = Object.freeze({
  [THUMBNAIL_VARIANTS.pin]: 96,
  [THUMBNAIL_VARIANTS.mini]: 240,
  [THUMBNAIL_VARIANTS.preview]: 480,
  [THUMBNAIL_VARIANTS.detail]: 960,
})

export const THUMBNAIL_UPLOAD_SPECS = Object.freeze([
  Object.freeze({ variant: THUMBNAIL_VARIANTS.pin, width: THUMBNAIL_WIDTHS.pin, quality: 0.72 }),
  Object.freeze({ variant: THUMBNAIL_VARIANTS.mini, width: THUMBNAIL_WIDTHS.mini, quality: 0.78 }),
  Object.freeze({ variant: THUMBNAIL_VARIANTS.preview, width: THUMBNAIL_WIDTHS.preview, quality: 0.82 }),
  Object.freeze({ variant: THUMBNAIL_VARIANTS.detail, width: THUMBNAIL_WIDTHS.detail, quality: 0.86 }),
])

const DEFAULT_THUMBNAIL_WIDTH = THUMBNAIL_WIDTHS.preview
const MIN_THUMBNAIL_WIDTH = THUMBNAIL_WIDTHS.pin
const MAX_THUMBNAIL_WIDTH = THUMBNAIL_WIDTHS.detail
const SUPPORTED_THUMBNAIL_WIDTHS = Object.freeze(Object.values(THUMBNAIL_WIDTHS))

function resolveThumbnailWidth(widthOrVariant) {
  const variantKey = String(widthOrVariant || '').trim().toLowerCase()
  if (variantKey && THUMBNAIL_WIDTHS[variantKey]) {
    return THUMBNAIL_WIDTHS[variantKey]
  }
  return widthOrVariant
}

function normalizeThumbnailWidth(widthOrVariant) {
  const numericWidth = Number(resolveThumbnailWidth(widthOrVariant))
  if (!Number.isFinite(numericWidth)) {
    return DEFAULT_THUMBNAIL_WIDTH
  }
  return Math.min(MAX_THUMBNAIL_WIDTH, Math.max(MIN_THUMBNAIL_WIDTH, Math.round(numericWidth)))
}

function selectSupportedThumbnailWidth(width) {
  const normalizedWidth = normalizeThumbnailWidth(width)
  return SUPPORTED_THUMBNAIL_WIDTHS.find((candidate) => normalizedWidth <= candidate) || SUPPORTED_THUMBNAIL_WIDTHS[SUPPORTED_THUMBNAIL_WIDTHS.length - 1]
}

function readImageElement(objectUrl) {
  return new Promise((resolve, reject) => {
    const image = new Image()
    image.onload = () => resolve(image)
    image.onerror = () => reject(new Error('Failed to load image preview.'))
    image.src = objectUrl
  })
}

function resolvePreviewSize(width, height, maxWidth, maxHeight) {
  if (!width || !height) {
    return { width: maxWidth, height: maxHeight }
  }

  const scale = Math.min(maxWidth / width, maxHeight / height, 1)
  return {
    width: Math.max(1, Math.round(width * scale)),
    height: Math.max(1, Math.round(height * scale)),
  }
}

function resolveWidthBoundedSize(width, height, maxWidth) {
  if (!width || !height) {
    return { width: maxWidth, height: maxWidth }
  }

  const scale = Math.min(maxWidth / width, 1)
  return {
    width: Math.max(1, Math.round(width * scale)),
    height: Math.max(1, Math.round(height * scale)),
  }
}

async function resolveImageOrientation(file) {
  try {
    return normalizeExifOrientation(await exifr.orientation(file))
  } catch {
    return 1
  }
}

async function resolveImageGps(file) {
  try {
    const gps = await exifr.gps(file)
    return {
      latitude: normalizeCoordinate(gps?.latitude),
      longitude: normalizeCoordinate(gps?.longitude),
    }
  } catch {
    return {
      latitude: null,
      longitude: null,
    }
  }
}

async function decodeImageSource(blob, objectUrl) {
  if (typeof createImageBitmap === 'function') {
    try {
      return await createImageBitmap(blob, { imageOrientation: 'from-image' })
    } catch {
      try {
        return await createImageBitmap(blob)
      } catch {
        // fall through to the image element decoder
      }
    }
  }

  if (objectUrl) {
    return readImageElement(objectUrl)
  }
  const temporaryUrl = URL.createObjectURL(blob)
  try {
    return await readImageElement(temporaryUrl)
  } finally {
    URL.revokeObjectURL(temporaryUrl)
  }
}

let browserAppliesOrientationPromise = null

// Measures once whether this browser applies EXIF Orientation while decoding:
// a tiny JPEG tagged Orientation 6 decodes portrait (height > width) when it does.
export function detectBrowserAppliesExifOrientation() {
  if (!browserAppliesOrientationPromise) {
    browserAppliesOrientationPromise = (async () => {
      if (typeof document === 'undefined') {
        return true
      }
      try {
        const canvas = document.createElement('canvas')
        canvas.width = 2
        canvas.height = 1
        const context = canvas.getContext('2d')
        if (!context) {
          return true
        }
        context.fillStyle = '#ffffff'
        context.fillRect(0, 0, 1, 1)
        context.fillStyle = '#000000'
        context.fillRect(1, 0, 1, 1)
        const jpeg = await new Promise((resolve) => canvas.toBlob(resolve, 'image/jpeg', 0.92))
        if (!jpeg) {
          return true
        }
        const tagged = insertExifSegment(new Uint8Array(await jpeg.arrayBuffer()), buildOrientationExifSegment(6))
        if (!tagged) {
          return true
        }
        const decoded = await decodeImageSource(new Blob([tagged], { type: 'image/jpeg' }))
        const width = decoded.width || decoded.naturalWidth
        const height = decoded.height || decoded.naturalHeight
        decoded.close?.()
        return height > width
      } catch {
        return true
      }
    })()
  }
  return browserAppliesOrientationPromise
}

function normalizeCoordinate(value) {
  const numericValue = Number(value)
  if (!Number.isFinite(numericValue)) {
    return null
  }
  return Number(numericValue.toFixed(7))
}

function preservesAlphaContentType(contentType) {
  const normalized = String(contentType || '').trim().toLowerCase()
  return normalized === 'image/png' || normalized === 'image/gif' || normalized === 'image/webp'
}

function resolvePreparedThumbnailContentType(contentType) {
  return preservesAlphaContentType(contentType) ? 'image/png' : 'image/jpeg'
}

async function renderOrientedImageBlob(image, orientation, targetWidth, targetHeight, mimeType, quality) {
  const canvas = document.createElement('canvas')
  canvas.width = Math.max(1, Math.round(targetWidth))
  canvas.height = Math.max(1, Math.round(targetHeight))

  const context = canvas.getContext('2d')
  if (!context) {
    throw new Error('Failed to initialize image canvas.')
  }

  context.imageSmoothingEnabled = true
  context.imageSmoothingQuality = 'high'
  drawOrientedImage(context, image, orientation, canvas.width, canvas.height)

  const blob = await new Promise((resolve) => {
    if (mimeType === 'image/jpeg') {
      canvas.toBlob(resolve, mimeType, quality)
      return
    }
    canvas.toBlob(resolve, mimeType)
  })

  if (!blob) {
    throw new Error('Failed to create image thumbnail.')
  }

  return blob
}

export function buildThumbnailUrl(url, width = THUMBNAIL_VARIANTS.preview) {
  const normalizedUrl = String(url || '').trim()
  if (!normalizedUrl) {
    return ''
  }

  const thumbnailWidth = selectSupportedThumbnailWidth(width)

  try {
    const baseOrigin = typeof window !== 'undefined' ? window.location.origin : 'http://localhost'
    const parsed = new URL(normalizedUrl, baseOrigin)
    parsed.searchParams.set('thumbnail', 'true')
    parsed.searchParams.set('w', String(thumbnailWidth))
    parsed.searchParams.set('tv', THUMBNAIL_CACHE_VERSION)

    if (parsed.origin === baseOrigin) {
      return `${parsed.pathname}${parsed.search}${parsed.hash}`
    }
    return parsed.toString()
  } catch {
    return normalizedUrl
  }
}

export async function prepareClientImageUpload(file) {
  if (!(file instanceof File) || !String(file.type || '').startsWith('image/')) {
    return {
      gpsLatitude: null,
      gpsLongitude: null,
      thumbnails: [],
      orientedThumbnails: false,
    }
  }

  const objectUrl = URL.createObjectURL(file)

  try {
    const [exifOrientation, gps, image, browserApplies] = await Promise.all([
      resolveImageOrientation(file),
      resolveImageGps(file),
      decodeImageSource(file, objectUrl),
      detectBrowserAppliesExifOrientation(),
    ])

    const orientation = resolveOrientationToApply(exifOrientation, browserApplies)
    const sourceWidth = image.width || image.naturalWidth
    const sourceHeight = image.height || image.naturalHeight
    const { width: orientedWidth, height: orientedHeight } = resolveOrientedSize(sourceWidth, sourceHeight, orientation)
    const thumbnailContentType = resolvePreparedThumbnailContentType(file.type)

    const thumbnails = []
    for (const spec of THUMBNAIL_UPLOAD_SPECS) {
      const thumbnailSize = resolveWidthBoundedSize(orientedWidth, orientedHeight, spec.width)
      const blob = await renderOrientedImageBlob(
        image,
        orientation,
        thumbnailSize.width,
        thumbnailSize.height,
        thumbnailContentType,
        spec.quality,
      )

      thumbnails.push({
        variant: spec.variant,
        contentType: thumbnailContentType,
        fileSize: blob.size,
        blob,
      })
    }

    image.close?.()

    return {
      gpsLatitude: gps.latitude,
      gpsLongitude: gps.longitude,
      thumbnails,
      orientedThumbnails: true,
    }
  } finally {
    URL.revokeObjectURL(objectUrl)
  }
}

export async function createLocalImagePreview(file, options = {}) {
  const originalPreviewUrl = URL.createObjectURL(file)

  if (!(file instanceof File) || !String(file.type || '').startsWith('image/')) {
    return originalPreviewUrl
  }

  const maxWidth = normalizeThumbnailWidth(options.maxWidth ?? 320)
  const maxHeight = normalizeThumbnailWidth(options.maxHeight ?? 240)
  const quality = Number.isFinite(Number(options.quality)) ? Number(options.quality) : 0.82

  try {
    const [exifOrientation, image, browserApplies] = await Promise.all([
      resolveImageOrientation(file),
      decodeImageSource(file, originalPreviewUrl),
      detectBrowserAppliesExifOrientation(),
    ])
    const orientation = resolveOrientationToApply(exifOrientation, browserApplies)
    const sourceWidth = image.width || image.naturalWidth
    const sourceHeight = image.height || image.naturalHeight
    const oriented = resolveOrientedSize(sourceWidth, sourceHeight, orientation)
    const previewSize = resolvePreviewSize(oriented.width, oriented.height, maxWidth, maxHeight)

    const previewBlob = await renderOrientedImageBlob(
      image,
      orientation,
      previewSize.width,
      previewSize.height,
      'image/jpeg',
      quality,
    )
    image.close?.()

    URL.revokeObjectURL(originalPreviewUrl)
    return URL.createObjectURL(previewBlob)
  } catch (error) {
    if (options.rejectOnDecodeError === true) {
      URL.revokeObjectURL(originalPreviewUrl)
      throw error
    }
    return originalPreviewUrl
  }
}
