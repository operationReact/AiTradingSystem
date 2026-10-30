/** Reads a theme token ("r g b" triplet) from the active CSS variables. */
function cssVar(name) {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
}

export function chartPalette() {
  const color = (name, alpha = 1) => `rgb(${cssVar(name)} / ${alpha})`;
  return {
    text: color('--chart-text'),
    muted: color('--chart-axis'),
    grid: color('--chart-grid', 0.6),
    accent: color('--chart-series-1'),
    accentSoft: color('--accent', 0.18),
    gain: color('--gain'),
    gainSoft: color('--gain', 0.15),
    loss: color('--loss'),
    lossSoft: color('--loss', 0.15),
    warn: color('--warn'),
    surface: color('--surface'),
    border: color('--border'),
    series: Array.from({ length: 7 }, (_, index) => color(`--chart-series-${index + 1}`)),
  };
}

export function baseOption(p) {
  return {
    animationDuration: Number.parseFloat(cssVar('--motion-normal')) || 0,
    textStyle: { color: p.muted, fontFamily: 'Inter, system-ui, sans-serif' },
    grid: { left: 8, right: 12, top: 28, bottom: 28, containLabel: true },
    tooltip: {
      backgroundColor: p.surface,
      borderColor: p.border,
      textStyle: { color: p.text, fontSize: 12 },
    },
  };
}
