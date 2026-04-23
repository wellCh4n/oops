import { Ref, useEffect, useRef } from "react"
import { FieldValues, UseFormReturn } from "react-hook-form"

import { ApplicationTabHandle } from "./application-tab-handle"
import { useApplicationTabHandle } from "./use-application-tab-handle"

interface UseApplicationEditorTabOptions<TValues extends FieldValues> {
  ref: Ref<ApplicationTabHandle>
  isReady?: boolean
  getSnapshot: () => string
  onSave: () => Promise<boolean>
  form?: UseFormReturn<TValues>
  onSubmit?: (values: TValues) => Promise<boolean>
  initializeBaselineWhenReady?: boolean
}

export function useApplicationEditorTab<TValues extends FieldValues = never>({
  ref,
  isReady = true,
  getSnapshot,
  onSave,
  form,
  onSubmit,
  initializeBaselineWhenReady = false,
}: UseApplicationEditorTabOptions<TValues>) {
  const handleSave = async () => {
    return onSave()
  }

  const baselineInitializedRef = useRef(false)

  const { captureBaseline } = useApplicationTabHandle({
    ref,
    isReady,
    getSnapshot,
    onSave: handleSave,
    captureBaselineAfterSave: true,
  })

  useEffect(() => {
    if (!initializeBaselineWhenReady || !isReady || baselineInitializedRef.current) {
      return
    }

    captureBaseline()
    baselineInitializedRef.current = true
  }, [captureBaseline, initializeBaselineWhenReady, isReady])

  const handleSubmit = form && onSubmit
    ? form.handleSubmit(async (values) => {
        const saved = await onSubmit(values)
        if (saved) {
          captureBaseline()
        }
      })
    : undefined

  return {
    captureBaseline,
    handleSave,
    handleSubmit,
  }
}
