export type AppColorSeed = string | {
  id?: string | null
  namespace?: string | null
  name?: string | null
}

interface AppIdentityColor {
  primary: string
  accent: string
  pattern: number
}

const APP_COLOR_PALETTE: readonly (readonly [string, string])[] = [
  ["hsl(211 82% 43%)", "hsl(38 94% 56%)"],
  ["hsl(145 65% 36%)", "hsl(315 76% 58%)"],
  ["hsl(4 73% 52%)", "hsl(188 78% 43%)"],
  ["hsl(264 72% 52%)", "hsl(82 70% 48%)"],
  ["hsl(33 90% 47%)", "hsl(225 78% 55%)"],
  ["hsl(171 76% 32%)", "hsl(345 82% 57%)"],
  ["hsl(328 74% 47%)", "hsl(160 68% 40%)"],
  ["hsl(48 92% 45%)", "hsl(252 68% 57%)"],
  ["hsl(196 86% 41%)", "hsl(18 90% 57%)"],
  ["hsl(122 56% 36%)", "hsl(286 68% 57%)"],
  ["hsl(353 73% 48%)", "hsl(176 72% 39%)"],
  ["hsl(276 64% 45%)", "hsl(45 92% 55%)"],
  ["hsl(231 72% 53%)", "hsl(13 84% 58%)"],
  ["hsl(154 62% 34%)", "hsl(300 70% 56%)"],
  ["hsl(19 82% 45%)", "hsl(202 82% 47%)"],
  ["hsl(246 74% 50%)", "hsl(92 72% 52%)"],
  ["hsl(338 66% 40%)", "hsl(158 72% 62%)"],
  ["hsl(207 88% 39%)", "hsl(28 92% 52%)"],
  ["hsl(88 48% 34%)", "hsl(268 72% 64%)"],
  ["hsl(320 78% 52%)", "hsl(181 74% 41%)"],
  ["hsl(223 70% 35%)", "hsl(50 92% 57%)"],
  ["hsl(27 86% 50%)", "hsl(184 70% 35%)"],
  ["hsl(259 68% 48%)", "hsl(36 96% 58%)"],
  ["hsl(164 70% 30%)", "hsl(355 76% 58%)"],
]

// Derive a stable identity marker from the visible application identity.
// A curated palette plus hard-edged patterns stays legible at small sizes.
export function appColor(seed: AppColorSeed): string {
  return appIdentityColor(seed).primary
}

export function appAccentColor(seed: AppColorSeed): string {
  return appIdentityColor(seed).accent
}

export function appIdentityBackground(seed: AppColorSeed): string {
  const { primary, accent, pattern } = appIdentityColor(seed)

  if (pattern === 0) {
    return `linear-gradient(90deg, ${primary} 0 60%, ${accent} 60% 100%)`
  }
  if (pattern === 1) {
    return `linear-gradient(180deg, ${primary} 0 60%, ${accent} 60% 100%)`
  }
  if (pattern === 2) {
    return `linear-gradient(135deg, ${primary} 0 50%, ${accent} 50% 100%)`
  }
  if (pattern === 3) {
    return `linear-gradient(45deg, ${primary} 0 50%, ${accent} 50% 100%)`
  }
  if (pattern === 4) {
    return `linear-gradient(90deg, ${accent} 0 24%, ${primary} 24% 76%, ${accent} 76% 100%)`
  }
  if (pattern === 5) {
    return `linear-gradient(180deg, ${accent} 0 24%, ${primary} 24% 76%, ${accent} 76% 100%)`
  }
  if (pattern === 6) {
    return `linear-gradient(135deg, ${accent} 0 22%, ${primary} 22% 78%, ${accent} 78% 100%)`
  }
  return `conic-gradient(from 45deg, ${primary} 0 25%, ${accent} 25% 50%, ${primary} 50% 75%, ${accent} 75% 100%)`
}

function appIdentityColor(seed: AppColorSeed): AppIdentityColor {
  const displayKey = appDisplayKey(seed)
  const identityKey = appIdentityKey(seed)
  const paletteIndex = (hashString(displayKey) >>> 0) % APP_COLOR_PALETTE.length
  const [primary, accent] = APP_COLOR_PALETTE[paletteIndex] ?? APP_COLOR_PALETTE[0]

  return {
    primary,
    accent,
    pattern: (hashString(`${identityKey}:pattern`) >>> 0) % 8,
  }
}

function appDisplayKey(seed: AppColorSeed): string {
  if (typeof seed === "string") {
    return seed
  }

  const namespace = seed.namespace?.trim() ?? ""
  const name = seed.name?.trim() ?? ""
  if (namespace || name) {
    return `${namespace}/${name}`
  }

  return seed.id ?? ""
}

function appIdentityKey(seed: AppColorSeed): string {
  if (typeof seed === "string") {
    return seed
  }

  return `${appDisplayKey(seed)}:${seed.id ?? ""}`
}

function hashString(value: string): number {
  let hash = 0x811c9dc5
  for (let index = 0; index < value.length; index++) {
    hash ^= value.charCodeAt(index)
    hash = Math.imul(hash, 0x01000193)
  }
  return hash
}
