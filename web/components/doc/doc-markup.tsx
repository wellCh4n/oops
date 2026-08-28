"use client"

import { ReactNode } from "react"
import { InlineCode } from "@/components/doc/code-block"

/**
 * Doc copy lives in the locale files as plain strings, so a tiny inline markup
 * dialect keeps a whole sentence translatable instead of splitting it around
 * JSX children: `text` renders as InlineCode, **text** as bold, and bold may
 * wrap code spans.
 *
 * Code spans are opaque: the `**` in a path glob like `/openapi/**` is text,
 * never a bold marker.
 */
export function DocMarkup({ text }: { text: string }) {
  return <>{tokenize(text)}</>
}

function tokenize(text: string): ReactNode[] {
  const nodes: ReactNode[] = []
  let buffer = ""
  let index = 0
  let key = 0

  const flush = () => {
    if (buffer) {
      nodes.push(buffer)
      buffer = ""
    }
  }

  while (index < text.length) {
    if (text[index] === "`") {
      const close = text.indexOf("`", index + 1)
      if (close === -1) {
        buffer += text[index]
        index += 1
        continue
      }
      flush()
      nodes.push(<InlineCode key={key++}>{text.slice(index + 1, close)}</InlineCode>)
      index = close + 1
      continue
    }

    if (text.startsWith("**", index)) {
      const close = findClosingBold(text, index + 2)
      if (close === -1) {
        buffer += "**"
        index += 2
        continue
      }
      flush()
      nodes.push(<strong key={key++}>{tokenize(text.slice(index + 2, close))}</strong>)
      index = close + 2
      continue
    }

    buffer += text[index]
    index += 1
  }

  flush()
  return nodes
}

/** Locates the `**` closing a bold run, stepping over code spans on the way. */
function findClosingBold(text: string, from: number): number {
  let index = from
  while (index < text.length) {
    if (text[index] === "`") {
      const close = text.indexOf("`", index + 1)
      index = close === -1 ? text.length : close + 1
      continue
    }
    if (text.startsWith("**", index)) {
      return index
    }
    index += 1
  }
  return -1
}
