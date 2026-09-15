import { useState } from 'react'

export function CopyButton({ value }: { value: string }) {
  const [copied, setCopied] = useState(false)

  async function handleCopy() {
    await navigator.clipboard.writeText(value)
    setCopied(true)
    setTimeout(() => setCopied(false), 1500)
  }

  return (
    <button
      type="button"
      onClick={handleCopy}
      className="shrink-0 rounded-[3px] border border-border px-2 py-1 font-mono text-xs text-text-muted hover:border-signal hover:text-text"
    >
      {copied ? 'Copied' : 'Copy'}
    </button>
  )
}
