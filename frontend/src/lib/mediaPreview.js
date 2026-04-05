import exifr from 'exifr'

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

function isRotatedOrientation(orientation) {
  return orientation >= 5 && orientation <= 8
}

function normalizeOrientation(orientation) {
  const numericOrientation = Number(orientation)
  return Number.isFinite(numericOrientation) ? numericOrientation : 1
}

async function resolveImageOrientation(file) {
  try {
    return normalizeOrientation(await exifr.orientation(file))
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

async function loadPreviewImage(file, objectUrl) {
  if (typeof createImageBitmap === 'function') {
    try {
      return await createImageBitmap(file, { imageOrientation: 'none' })
    } catch {
      return await createImageBitmap(file)
    }
  }

  return readImageElement(objectUrl)
}

function drawOrientedPreview(context, image, orientation, targetWidth, targetHeight) {
  const requiresSwappedDrawSize = isRotatedOrientation(orientation)
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
  drawOrientedPreview(context, image, orientation, canvas.width, canvas.height)

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
    }
  }

  const objectUrl = URL.createObjectURL(file)

  try {
    const [orientation, gps, image] = await Promise.all([
      resolveImageOrientation(file),
      resolveImageGps(file),
      loadPreviewImage(file, objectUrl),
    ])

    const sourceWidth = image.width || image.naturalWidth
    const sourceHeight = image.height || image.naturalHeight
    const orientedWidth = isRotatedOrientation(orientation) ? sourceHeight : sourceWidth
    const orientedHeight = isRotatedOrientation(orientation) ? sourceWidth : sourceHeight
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

    return {
      gpsLatitude: gps.latitude,
      gpsLongitude: gps.longitude,
      thumbnails,
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
    const orientation = await resolveImageOrientation(file)
    const image = await loadPreviewImage(file, originalPreviewUrl)
    const sourceWidth = image.width || image.naturalWidth
    const sourceHeight = image.height || image.naturalHeight
    const previewSize = resolvePreviewSize(
      isRotatedOrientation(orientation) ? sourceHeight : sourceWidth,
      isRotatedOrientation(orientation) ? sourceWidth : sourceHeight,
      maxWidth,
      maxHeight,
    )

    const previewBlob = await renderOrientedImageBlob(
      image,
      orientation,
      previewSize.width,
      previewSize.height,
      'image/jpeg',
      quality,
    )

    URL.revokeObjectURL(originalPreviewUrl)
    return URL.createObjectURL(previewBlob)
  } catch {
    return originalPreviewUrl
  }
}
