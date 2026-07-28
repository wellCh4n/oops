// Curated emoji set offered as application icons. Deliberately small and
// PaaS-flavoured — a full emoji catalogue is a worse picker for this job, since
// nobody browses 1800 emojis to mark a service. Categories are label keys so the
// picker stays translated; the emojis themselves need no translation.
export interface AppIconCategory {
  labelKey: string
  emojis: readonly string[]
}

export const APP_ICON_CATEGORIES: readonly AppIconCategory[] = [
  {
    labelKey: "apps.icon.category.service",
    emojis: ["🌐", "🖥️", "☁️", "📡", "🛰️", "🔌", "🚦", "🕸️", "🔗", "🛡️", "🚪", "📶", "🧭", "🔀"],
  },
  {
    labelKey: "apps.icon.category.data",
    emojis: ["🗄️", "💾", "📊", "📈", "📉", "🗃️", "🧮", "📦", "🔢", "🧊", "📇", "🗂️"],
  },
  {
    labelKey: "apps.icon.category.tool",
    emojis: ["🔧", "🔨", "⚙️", "🧰", "🪛", "🧪", "🔍", "⏱️", "🧹", "🩺", "🔑", "🪝", "🧯"],
  },
  {
    labelKey: "apps.icon.category.ai",
    emojis: ["🤖", "🧠", "✨", "🔮", "🧬", "🦾", "🛸", "👁️", "💡"],
  },
  {
    labelKey: "apps.icon.category.business",
    emojis: ["🛒", "💳", "💰", "🧾", "🏷️", "📬", "📅", "🎫", "🏬", "🚚", "📞", "👥", "📢", "✉️"],
  },
  {
    labelKey: "apps.icon.category.mark",
    emojis: [
      "🚀", "🔥", "⚡", "🌟", "🎯", "🐳", "🐙", "🐧", "🦊", "🐝", "🍕", "☕",
      "🎮", "🧩", "🏆", "❤️", "🌈", "🍀", "🌵", "🎨", "🎬", "🎵", "📚", "📝",
    ],
  },
]

const ALLOWED_APP_ICONS: ReadonlySet<string> = new Set(
  APP_ICON_CATEGORIES.flatMap((category) => category.emojis)
)

// Icons round-trip through the API, where an older client or a direct OpenAPI
// caller could have stored something outside the curated set. Render only what
// we offer, so an unexpected value falls back to the derived color block rather
// than putting arbitrary text where an icon belongs.
export function normalizeAppIcon(icon?: string | null): string | undefined {
  if (!icon) {
    return undefined
  }
  const trimmed = icon.trim()
  return ALLOWED_APP_ICONS.has(trimmed) ? trimmed : undefined
}
