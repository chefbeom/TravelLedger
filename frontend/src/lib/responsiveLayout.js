// Touch phones should retain their mobile layout after rotating to landscape.
export const MOBILE_LAYOUT_QUERY = '(max-width: 760px), (max-width: 1100px) and (pointer: coarse)'

export function resolveLayoutMode(preference, matchesMobile) {
  return preference === 'mobile' || preference === 'desktop'
    ? preference
    : matchesMobile ? 'mobile' : 'desktop'
}
