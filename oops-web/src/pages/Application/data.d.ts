export type App = {
  id: number,
  appName: string,
  description: string,
  namespace: string,
  requestCoreCount: number,
  limitCoreCount: number,
  requestMemoryCount: number,
  limitMemoryCount: number
}