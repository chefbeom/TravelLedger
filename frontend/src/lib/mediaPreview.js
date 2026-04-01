import exifr from 'exifr'

const DEFAULT_THUMBNAIL_WIDTH = 480
const MIN_THUMBNAIL_WIDTH = 120
const MAX_THUMBNAIL_WIDTH = 960

function normalizeThumbnailWidth(width) {
  const numericWidth = Number(width)
  if (!Number.isFinite(numericWidth)) {
    return DEFAULT_THUMBNAIL_WIDTH
  }
  return Math.min(MAX_THUMBNAIL_WIDTH, Math.max(MIN_THUMBNAIL_WIDTH, Math.round(numericWidth)))
}

function readImageElement(objectUrl) {
  return new Promise((resolve, reject) => {
    const image = new Image()
    image.onload = () => resolve(image)
    image.onerror = () => reject(new Error('이미지 미리보기를 불러오지 못했습니다.'))
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

export function buildThumbnailUrl(url, width = DEFAULT_THUMBNAIL_WIDTH) {
  const normalizedUrl = String(url || '').trim()
  if (!normalizedUrl) {
    return ''
  }

  const thumbnailWidth = normalizeThumbnailWidth(width)

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

    const canvas = document.createElement('canvas')
    canvas.width = previewSize.width
    canvas.height = previewSize.height

    const context = canvas.getContext('2d')
    if (!context) {
      throw new Error('미리보기 캔버스를 초기화하지 못했습니다.')
    }

    context.imageSmoothingEnabled = true
    context.imageSmoothingQuality = 'high'
    drawOrientedPreview(context, image, orientation, previewSize.width, previewSize.height)

    const previewBlob = await new Promise((resolve) => {
      canvas.toBlob(resolve, 'image/jpeg', quality)
    })

    if (!previewBlob) {
      throw new Error('미리보기 이미지를 생성하지 못했습니다.')
    }

    URL.revokeObjectURL(originalPreviewUrl)
    return URL.createObjectURL(previewBlob)
  } catch {
    return originalPreviewUrl
  }
}
