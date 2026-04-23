import { Ref, useCallback, useImperativeHandle, useRef } from "react"

import { ApplicationTabHandle } from "./application-tab-handle"

interface UseApplicationTabHandleOptions {
  ref: Ref<ApplicationTabHandle>
  isReady?: boolean
  getSnapshot: () => string
  onSave: () => Promise<boolean>
  captureBaselineAfterSave?: boolean
}

export function useApplicationTabHandle({
  ref,
  isReady = true,
  getSnapshot,
  onSave,
  captureBaselineAfterSave = false,
}: UseApplicationTabHandleOptions) {
  const baselineRef = useRef<string | null>(null)

  const captureBaseline = useCallback(() => {
    baselineRef.current = getSnapshot()
  }, [getSnapshot])

  const hasUnsavedChanges = useCallback(() => {
    if (!isReady || baselineRef.current == null) {
      return false
    }

    return getSnapshot() !== baselineRef.current
  }, [getSnapshot, isReady])

  useImperativeHandle(ref, () => ({
    hasUnsavedChanges,
    async save() {
      if (!isReady) {
        return true
      }

      const saved = await onSave()
      if (saved && captureBaselineAfterSave) {
        baselineRef.current = getSnapshot()
      }

      return saved
    },
  }), [captureBaselineAfterSave, getSnapshot, hasUnsavedChanges, isReady, onSave])

  return {
    captureBaseline,
  }
}
