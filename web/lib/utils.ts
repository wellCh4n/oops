import { clsx, type ClassValue } from "clsx"
import { twMerge } from "tailwind-merge"

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

// Resource name constraints: lowercase letters, digits, hyphens only.
// Must start and end with a letter or digit, max 24 chars.
export const NAME_MAX_LENGTH = 24
export const NAME_REGEX = /^[a-z]([-a-z0-9]*[a-z0-9])?$/

// Environment name constraint is slightly looser: uppercase letters are allowed
// because environment names are internal-only and never used as K8s resource names.
export const ENVIRONMENT_NAME_REGEX = /^[A-Za-z]([-A-Za-z0-9]*[A-Za-z0-9])?$/
