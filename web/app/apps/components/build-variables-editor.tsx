"use client"

import { Control, useFieldArray, useFormState } from "react-hook-form"
import { Plus, Variable, X } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { useLanguage } from "@/contexts/language-context"
import { ApplicationBuildFormValues } from "../schema"

interface BuildVariablesEditorProps {
  control: Control<ApplicationBuildFormValues>
  /** Index of the environment being edited. Mount with `key={environmentIndex}`: the field array binds its name once. */
  environmentIndex: number
}

/**
 * The build variables of one environment, as plain name/value rows. Values are shown as typed —
 * a build arg stays readable in the image history, so masking it here would only suggest that
 * this is somewhere to keep a secret.
 */
export function BuildVariablesEditor({ control, environmentIndex }: BuildVariablesEditorProps) {
  const { t } = useLanguage()
  const name = `environmentConfigs.${environmentIndex}.buildVariables` as const
  const { fields, append, remove } = useFieldArray({ control, name })
  const { errors } = useFormState({ control, name })
  const rowErrors = errors.environmentConfigs?.[environmentIndex]?.buildVariables

  return (
    <div className="grid gap-2">
      <Label className="flex items-center gap-1">
        <Variable className="size-3.5" />
        {t("apps.build.variables")}
      </Label>
      <p className="text-xs text-muted-foreground">{t("apps.build.variablesHint")}</p>

      {fields.map((field, index) => {
        const nameError = rowErrors?.[index]?.name?.message
        return (
          <div key={field.id} className="grid gap-1">
            <div className="flex items-center gap-2">
              <Input
                autoComplete="off"
                spellCheck={false}
                className="flex-1 font-mono text-sm"
                placeholder={t("apps.build.variableNamePlaceholder")}
                aria-invalid={!!nameError}
                {...control.register(`${name}.${index}.name`)}
              />
              <Input
                autoComplete="off"
                spellCheck={false}
                className="flex-[2] font-mono text-sm"
                placeholder={t("apps.build.variableValuePlaceholder")}
                {...control.register(`${name}.${index}.value`)}
              />
              <Button
                type="button"
                variant="ghost"
                size="icon"
                className="shrink-0 cursor-pointer text-muted-foreground"
                aria-label={t("apps.build.variableRemove")}
                onClick={() => remove(index)}
              >
                <X className="size-4" />
              </Button>
            </div>
            {nameError && <p className="text-xs text-destructive">{t(nameError)}</p>}
          </div>
        )
      })}

      <div>
        <Button
          type="button"
          variant="outline"
          size="sm"
          className="cursor-pointer"
          onClick={() => append({ name: "", value: "" })}
        >
          <Plus className="size-4" />
          {t("apps.build.variableAdd")}
        </Button>
      </div>
    </div>
  )
}
