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
    const image = typeof createImageBitmap === 'function'
      ? await createImageBitmap(file)
      : await readImageElement(originalPreviewUrl)

    const previewSize = resolvePreviewSize(
      image.width || image.naturalWidth,
      image.height || image.naturalHeight,
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
    context.drawImage(image, 0, 0, previewSize.width, previewSize.height)

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
