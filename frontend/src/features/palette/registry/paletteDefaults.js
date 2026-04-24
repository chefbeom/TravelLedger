export const paletteTemplates = [
  {
    id: 'kpi-today',
    type: 'kpi',
    label: '오늘 KPI',
    defaultSize: '1x1',
    options: { variant: 'today' },
  },
  {
    id: 'kpi-week',
    type: 'kpi',
    label: '이번 주 KPI',
    defaultSize: '1x1',
    options: { variant: 'week' },
  },
  {
    id: 'kpi-month',
    type: 'kpi',
    label: '이번 달 KPI',
    defaultSize: '2x2',
    options: { variant: 'month' },
  },
  {
    id: 'kpi-year',
    type: 'kpi',
    label: '올해 KPI',
    defaultSize: '2x2',
    options: { variant: 'year' },
  },
  {
    id: 'kpi-income-expense',
    type: 'kpi',
    label: '수입/지출 균형',
    defaultSize: '3x2',
    options: { variant: 'incomeExpense' },
  },
  {
    id: 'kpi-recent-flow',
    type: 'kpi',
    label: '최근 흐름',
    defaultSize: '3x2',
    options: { variant: 'recentFlow' },
  },
  {
    id: 'calendar-month',
    type: 'calendar',
    label: '월 달력',
    defaultSize: '3x3',
    options: {},
  },
]

export function createDefaultPalettePresets() {
  return [
    {
      id: 1,
      name: '일정/업무 집중',
      palettes: [
        { id: 'preset1-calendar', type: 'calendar', size: '3x3', position: { x: 0, y: 0 }, visible: true, options: {} },
        { id: 'preset1-today', type: 'kpi', size: '1x1', position: { x: 3, y: 0 }, visible: true, options: { variant: 'today' } },
        { id: 'preset1-week', type: 'kpi', size: '1x1', position: { x: 4, y: 0 }, visible: true, options: { variant: 'week' } },
        { id: 'preset1-recent', type: 'kpi', size: '3x2', position: { x: 5, y: 0 }, visible: true, options: { variant: 'recentFlow' } },
        { id: 'preset1-month', type: 'kpi', size: '2x2', position: { x: 3, y: 1 }, visible: true, options: { variant: 'month' } },
      ],
    },
    {
      id: 2,
      name: '전체 요약',
      palettes: [
        { id: 'preset2-month', type: 'kpi', size: '2x2', position: { x: 0, y: 0 }, visible: true, options: { variant: 'month' } },
        { id: 'preset2-year', type: 'kpi', size: '2x2', position: { x: 2, y: 0 }, visible: true, options: { variant: 'year' } },
        { id: 'preset2-balance', type: 'kpi', size: '3x2', position: { x: 4, y: 0 }, visible: true, options: { variant: 'incomeExpense' } },
        { id: 'preset2-today', type: 'kpi', size: '1x1', position: { x: 7, y: 0 }, visible: true, options: { variant: 'today' } },
        { id: 'preset2-week', type: 'kpi', size: '1x1', position: { x: 8, y: 0 }, visible: true, options: { variant: 'week' } },
        { id: 'preset2-calendar', type: 'calendar', size: '3x3', position: { x: 0, y: 2 }, visible: true, options: {} },
        { id: 'preset2-recent', type: 'kpi', size: '3x2', position: { x: 3, y: 2 }, visible: true, options: { variant: 'recentFlow' } },
      ],
    },
    {
      id: 3,
      name: '기본',
      palettes: [
        { id: 'preset3-today', type: 'kpi', size: '1x1', position: { x: 0, y: 0 }, visible: true, options: { variant: 'today' } },
        { id: 'preset3-week', type: 'kpi', size: '1x1', position: { x: 1, y: 0 }, visible: true, options: { variant: 'week' } },
        { id: 'preset3-month', type: 'kpi', size: '2x2', position: { x: 2, y: 0 }, visible: true, options: { variant: 'month' } },
        { id: 'preset3-calendar', type: 'calendar', size: '3x3', position: { x: 4, y: 0 }, visible: true, options: {} },
        { id: 'preset3-balance', type: 'kpi', size: '3x2', position: { x: 0, y: 2 }, visible: true, options: { variant: 'incomeExpense' } },
        { id: 'preset3-recent', type: 'kpi', size: '2x2', position: { x: 7, y: 0 }, visible: true, options: { variant: 'recentFlow' } },
      ],
    },
  ]
}
