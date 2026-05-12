// Derive a stable pastel color from an application name.
// Uses FNV-1a so short, similar names hash to far-apart values, and varies
// saturation/lightness within a soft band so the perceptual color space is
// much larger than 360 hues alone.
export function appColor(name: string): string {
  let hash = 0x811c9dc5
  for (let index = 0; index < name.length; index++) {
    hash ^= name.charCodeAt(index)
    hash = Math.imul(hash, 0x01000193)
  }
  const unsigned = hash >>> 0
  const hue = unsigned % 360
  const saturation = 45 + ((unsigned >>> 9) % 21)   // 45..65
  const lightness = 60 + ((unsigned >>> 17) % 13)   // 60..72
  return `hsl(${hue}, ${saturation}%, ${lightness}%)`
}
