import { Button as ButtonPrimitive } from "@base-ui/react/button"
import { cva, type VariantProps } from "class-variance-authority"

import { cn } from "@/lib/utils"

const buttonVariants = cva(
  // No border on the base: base-vega puts `border border-transparent` here, which
  // makes every button 2px wider than it was pre-migration. `outline` carries its
  // own border instead, matching the original box metrics exactly.
  "group/button inline-flex shrink-0 cursor-pointer items-center justify-center rounded-md text-sm font-medium whitespace-nowrap transition-all outline-none select-none disabled:cursor-not-allowed focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 active:not-aria-[haspopup]:translate-y-px disabled:pointer-events-none disabled:opacity-50 aria-invalid:border-destructive aria-invalid:ring-3 aria-invalid:ring-destructive/20 dark:aria-invalid:border-destructive/50 dark:aria-invalid:ring-destructive/40 [&_svg]:pointer-events-none [&_svg]:shrink-0 [&_svg:not([class*='size-'])]:size-4",
  {
    variants: {
      variant: {
        default: "bg-primary text-primary-foreground hover:bg-primary/80",
        outline:
          "border border-border bg-background shadow-xs hover:bg-muted hover:text-foreground aria-expanded:bg-muted aria-expanded:text-foreground dark:border-input dark:bg-input/30 dark:hover:bg-input/50",
        secondary:
          "bg-secondary text-secondary-foreground hover:bg-[color-mix(in_oklch,var(--secondary),var(--foreground)_5%)] aria-expanded:bg-secondary aria-expanded:text-secondary-foreground",
        ghost:
          "hover:bg-muted hover:text-foreground aria-expanded:bg-muted aria-expanded:text-foreground dark:hover:bg-muted/50",
        // The three semantic variants below are the project's own, restored to
        // their pre-migration appearance: destructive stays a solid red so a
        // delete action still reads as one, warning/success stay outlined.
        destructive:
          "bg-destructive text-white hover:bg-destructive/90 focus-visible:ring-destructive/20 dark:bg-destructive/60 dark:focus-visible:ring-destructive/40",
        warning:
          "border border-warning/40 bg-transparent text-warning shadow-xs hover:border-warning/60 hover:bg-warning/10 hover:text-warning focus-visible:ring-warning/20 dark:focus-visible:ring-warning/40",
        success:
          "border border-success/40 bg-transparent text-success shadow-xs hover:border-success/60 hover:bg-success/10 hover:text-success focus-visible:ring-success/20 dark:focus-visible:ring-success/40",
        link: "text-primary underline-offset-4 hover:underline",
      },
      size: {
        // base-vega sizes its icon padding off a `data-icon` attribute that the
        // lucide icons used throughout this app never emit, so every button with
        // an icon collapsed to the tightest padding. The `has-[>svg]:` rules below
        // are the project's original scale and do match plain SVG children.
        default:
          "h-9 gap-2 px-4 py-2 has-[>svg]:px-3 in-data-[slot=button-group]:rounded-md",
        xs: "h-6 gap-1 rounded-[min(var(--radius-md),8px)] px-2 text-xs has-[>svg]:px-1.5 in-data-[slot=button-group]:rounded-md [&_svg:not([class*='size-'])]:size-3",
        sm: "h-8 gap-1.5 rounded-[min(var(--radius-md),10px)] px-3 has-[>svg]:px-2.5 in-data-[slot=button-group]:rounded-md",
        lg: "h-10 gap-2 px-6 has-[>svg]:px-4",
        icon: "size-9",
        "icon-xs":
          "size-6 rounded-[min(var(--radius-md),8px)] in-data-[slot=button-group]:rounded-md [&_svg:not([class*='size-'])]:size-3",
        "icon-sm":
          "size-8 rounded-[min(var(--radius-md),10px)] in-data-[slot=button-group]:rounded-md",
        "icon-lg": "size-10",
      },
    },
    defaultVariants: {
      variant: "default",
      size: "default",
    },
  }
)

function Button({
  className,
  variant = "default",
  size = "default",
  ...props
}: ButtonPrimitive.Props & VariantProps<typeof buttonVariants>) {
  return (
    <ButtonPrimitive
      data-slot="button"
      className={cn(buttonVariants({ variant, size, className }))}
      {...props}
    />
  )
}

export { Button, buttonVariants }
