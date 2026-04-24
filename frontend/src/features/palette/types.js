export const DASHBOARD_GRID_COLUMNS = 9

export const PALETTE_SIZES = ['1x1', '1x2', '2x2', '3x2', '3x3']

/**
 * @typedef {'1x1' | '1x2' | '2x2' | '3x2' | '3x3'} PaletteSize
 */

/**
 * @typedef {{ x: number, y: number }} PalettePosition
 */

/**
 * @typedef {{
 *   id: string,
 *   type: string,
 *   size: PaletteSize,
 *   position: PalettePosition,
 *   visible: boolean,
 *   options?: Record<string, unknown>
 * }} PaletteConfig
 */

/**
 * @typedef {{
 *   id: 1 | 2 | 3,
 *   name: string,
 *   palettes: PaletteConfig[]
 * }} PalettePreset
 */
