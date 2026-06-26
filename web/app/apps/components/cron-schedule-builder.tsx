"use client"

import { useEffect, useRef, useState } from "react"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Input } from "@/components/ui/input"
import { cn } from "@/lib/utils"

type Frequency = "daily" | "weekly" | "monthly" | "custom"

interface CronState {
  frequency: Frequency
  hour: number
  minute: number
  daysOfWeek: number[] // 0 = Sunday … 6 = Saturday
  dayOfMonth: number
  custom: string
}

const DEFAULT_STATE: CronState = {
  frequency: "daily",
  hour: 3,
  minute: 0,
  daysOfWeek: [1],
  dayOfMonth: 1,
  custom: "",
}

// Display order Monday … Sunday; values stay 0=Sunday for cron output.
const WEEKDAY_ORDER = [1, 2, 3, 4, 5, 6, 0]

function isMinuteValue(value: number) {
  return Number.isInteger(value) && value >= 0 && value <= 59
}

function isHourValue(value: number) {
  return Number.isInteger(value) && value >= 0 && value <= 23
}

/** Parse a 5-field cron back into the structured builder state. Anything we can't model falls into custom mode. */
function parseCron(expression: string): CronState {
  const trimmed = expression?.trim() ?? ""
  if (!trimmed) {
    return { ...DEFAULT_STATE }
  }
  const fallback: CronState = { ...DEFAULT_STATE, frequency: "custom", custom: trimmed }
  const fields = trimmed.split(/\s+/)
  if (fields.length !== 5) {
    return fallback
  }
  const [minuteField, hourField, dayOfMonthField, monthField, dayOfWeekField] = fields
  const minute = Number(minuteField)
  const hour = Number(hourField)
  if (!isMinuteValue(minute) || !isHourValue(hour) || monthField !== "*") {
    return fallback
  }
  if (dayOfMonthField === "*" && dayOfWeekField === "*") {
    return { ...DEFAULT_STATE, frequency: "daily", hour, minute }
  }
  if (dayOfMonthField === "*" && dayOfWeekField !== "*") {
    const days = dayOfWeekField.split(",").map(Number)
    if (days.some((day) => !Number.isInteger(day) || day < 0 || day > 7)) {
      return fallback
    }
    const normalized = Array.from(new Set(days.map((day) => (day === 7 ? 0 : day))))
    return { ...DEFAULT_STATE, frequency: "weekly", hour, minute, daysOfWeek: normalized }
  }
  if (dayOfMonthField !== "*" && dayOfWeekField === "*") {
    const dayOfMonth = Number(dayOfMonthField)
    if (!Number.isInteger(dayOfMonth) || dayOfMonth < 1 || dayOfMonth > 31) {
      return fallback
    }
    return { ...DEFAULT_STATE, frequency: "monthly", hour, minute, dayOfMonth }
  }
  return fallback
}

/** Render the structured builder state back into a 5-field cron expression. */
function buildCron(state: CronState): string {
  if (state.frequency === "custom") {
    return state.custom.trim()
  }
  const time = `${state.minute} ${state.hour}`
  switch (state.frequency) {
    case "daily":
      return `${time} * * *`
    case "weekly": {
      const days = state.daysOfWeek.length
        ? [...state.daysOfWeek].sort((left, right) => left - right).join(",")
        : "*"
      return `${time} * * ${days}`
    }
    case "monthly":
      return `${time} ${state.dayOfMonth} * *`
  }
}

function pad(value: number) {
  return value.toString().padStart(2, "0")
}

interface CronScheduleBuilderProps {
  value: string
  onChange: (cron: string) => void
  locale: string
  t: (key: string) => string
}

export function CronScheduleBuilder({ value, onChange, locale, t }: CronScheduleBuilderProps) {
  const [state, setState] = useState<CronState>(() => parseCron(value))
  const lastEmitted = useRef<string | null>(null)

  // On mount with a blank value, emit the default daily expression so the form holds a concrete cron.
  useEffect(() => {
    if (!value?.trim()) {
      const cron = buildCron(state)
      lastEmitted.current = cron
      onChange(cron)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // Re-sync from the outside (tab switch, form reset) when the value diverges from what we last emitted.
  useEffect(() => {
    if (value !== lastEmitted.current) {
      setState(parseCron(value))
      lastEmitted.current = value
    }
  }, [value])

  const update = (partial: Partial<CronState>) => {
    const next = { ...state, ...partial }
    setState(next)
    const cron = buildCron(next)
    lastEmitted.current = cron
    onChange(cron)
  }

  const toggleWeekday = (day: number) => {
    const selected = state.daysOfWeek.includes(day)
    // Keep at least one weekday selected so the expression stays valid.
    if (selected && state.daysOfWeek.length === 1) return
    const daysOfWeek = selected
      ? state.daysOfWeek.filter((existing) => existing !== day)
      : [...state.daysOfWeek, day]
    update({ daysOfWeek })
  }

  const weekdayLabel = (day: number) =>
    new Intl.DateTimeFormat(locale, { weekday: "short" }).format(new Date(Date.UTC(2024, 0, 7 + day)))

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-2">
        <Select value={state.frequency} onValueChange={(frequency) => update({ frequency: frequency as Frequency })}>
          <SelectTrigger className="w-40 cursor-pointer">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="daily">{t("apps.expertConfig.scheduledRestartDaily")}</SelectItem>
            <SelectItem value="weekly">{t("apps.expertConfig.scheduledRestartWeekly")}</SelectItem>
            <SelectItem value="monthly">{t("apps.expertConfig.scheduledRestartMonthly")}</SelectItem>
            <SelectItem value="custom">{t("apps.expertConfig.scheduledRestartCustom")}</SelectItem>
          </SelectContent>
        </Select>

        {state.frequency === "monthly" && (
          <div className="flex items-center gap-2">
            <span className="text-sm text-muted-foreground">{t("apps.expertConfig.scheduledRestartDayOfMonthLabel")}</span>
            <Select value={String(state.dayOfMonth)} onValueChange={(day) => update({ dayOfMonth: Number(day) })}>
              <SelectTrigger className="w-20 cursor-pointer">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {Array.from({ length: 31 }, (_, index) => index + 1).map((day) => (
                  <SelectItem key={day} value={String(day)}>{day}</SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
        )}

        {state.frequency !== "custom" && (
          <div className="flex items-center gap-2">
            <span className="text-sm text-muted-foreground">{t("apps.expertConfig.scheduledRestartTimeLabel")}</span>
            <Select value={String(state.hour)} onValueChange={(hour) => update({ hour: Number(hour) })}>
              <SelectTrigger className="w-20 cursor-pointer">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {Array.from({ length: 24 }, (_, index) => index).map((hour) => (
                  <SelectItem key={hour} value={String(hour)}>{pad(hour)}</SelectItem>
                ))}
              </SelectContent>
            </Select>
            <span className="text-sm text-muted-foreground">:</span>
            <Select value={String(state.minute)} onValueChange={(minute) => update({ minute: Number(minute) })}>
              <SelectTrigger className="w-20 cursor-pointer">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {Array.from({ length: 60 }, (_, index) => index).map((minute) => (
                  <SelectItem key={minute} value={String(minute)}>{pad(minute)}</SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
        )}
      </div>

      {state.frequency === "weekly" && (
        <div className="flex flex-col gap-1.5">
          <span className="text-sm text-muted-foreground">{t("apps.expertConfig.scheduledRestartWeekdaysLabel")}</span>
          <div className="flex flex-wrap gap-1.5">
            {WEEKDAY_ORDER.map((day) => {
              const active = state.daysOfWeek.includes(day)
              return (
                <button
                  key={day}
                  type="button"
                  onClick={() => toggleWeekday(day)}
                  className={cn(
                    "min-w-11 rounded-md border px-2 py-1 text-xs cursor-pointer transition-colors",
                    active
                      ? "border-primary bg-primary text-primary-foreground"
                      : "border-input bg-background hover:bg-accent",
                  )}
                >
                  {weekdayLabel(day)}
                </button>
              )
            })}
          </div>
        </div>
      )}

      {state.frequency === "custom" && (
        <Input
          value={state.custom}
          onChange={(event) => update({ custom: event.target.value })}
          placeholder="0 3 * * *"
          className="w-64 font-mono"
        />
      )}
    </div>
  )
}
