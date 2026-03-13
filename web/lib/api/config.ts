
export const API_BASE_URL = (() => {
  const configured = process.env.NEXT_PUBLIC_API_URL?.trim()
  if (configured && configured.length > 0) {
    return configured.replace(/\/$/, "")
  }
  if (process.env.NODE_ENV === "development") return "http://localhost:8080"
  return ""
})()
