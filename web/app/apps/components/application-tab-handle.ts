export interface ApplicationTabHandle {
  hasUnsavedChanges: () => boolean
  save: () => Promise<boolean>
}
