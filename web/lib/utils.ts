import { clsx, type ClassValue } from "clsx"
import { twMerge } from "tailwind-merge"

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

// Resource name constraints: lowercase letters, digits, hyphens only.
// Must start and end with a letter or digit, max 24 chars.
export const NAME_MAX_LENGTH = 24
export const NAME_REGEX = /^[a-z0-9]([-a-z0-9]*[a-z0-9])?$/

export function nameMessage(t: (key: string) => string) {
  return t("validation.nameInvalid")
}

export function nameMaxLengthMessage(t: (key: string) => string) {
  return t("validation.nameMaxLength")
}
