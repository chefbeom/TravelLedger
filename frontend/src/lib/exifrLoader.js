let exifrModule = null

export function loadExifr() {
  exifrModule ||= import('exifr').then((module) => module.default ?? module)
  return exifrModule
}
