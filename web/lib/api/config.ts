
export const API_BASE_URL = (() => {
  if (process.env.NEXT_PUBLIC_API_URL && process.env.NEXT_PUBLIC_API_URL.length > 0) {
    return process.env.NEXT_PUBLIC_API_URL.replace(/\/$/, "")
  }
  if (typeof window === "undefined") {
    if (process.env.NODE_ENV === "development") {
      return "http://localhost:8080"
    }
    return ""
  }
  return ""
})()
