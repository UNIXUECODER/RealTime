import { useState } from 'react'

interface JsonTreeViewProps {
  data: unknown
  depth?: number
}

/** Small custom recursive renderer rather than a third-party JSON-tree library —
 * those ship their own CSS that fights Tailwind, and the recursion itself is simple
 * enough to own outright. Nodes below depth 1 start collapsed, so a deeply nested
 * payload doesn't render as one huge wall on arrival. */
export function JsonTreeView({ data, depth = 0 }: JsonTreeViewProps) {
  if (data === null || data === undefined) {
    return <span className="text-text-muted">null</span>
  }
  if (typeof data === 'boolean' || typeof data === 'number') {
    return <span className="text-signal">{String(data)}</span>
  }
  if (typeof data === 'string') {
    return <span className="text-success">&quot;{data}&quot;</span>
  }
  if (Array.isArray(data)) {
    return <JsonNodeList entries={data.map((value, index) => [String(index), value] as const)} bracket={['[', ']']} depth={depth} />
  }
  return <JsonNodeList entries={Object.entries(data as Record<string, unknown>)} bracket={['{', '}']} depth={depth} />
}

function JsonNodeList({
  entries,
  bracket,
  depth,
}: {
  entries: readonly (readonly [string, unknown])[]
  bracket: [string, string]
  depth: number
}) {
  const [isExpanded, setIsExpanded] = useState(depth < 1)

  if (entries.length === 0) {
    return <span className="text-text-muted">{bracket[0]}{bracket[1]}</span>
  }

  if (!isExpanded) {
    return (
      <button type="button" onClick={() => setIsExpanded(true)} className="text-text-muted hover:text-text">
        {bracket[0]}&hellip;{bracket[1]} <span className="text-text-muted">({entries.length})</span>
      </button>
    )
  }

  return (
    <span>
      <button type="button" onClick={() => setIsExpanded(false)} className="text-text-muted hover:text-text">
        {bracket[0]}
      </button>
      <div className="ml-4 border-l border-border pl-3">
        {entries.map(([key, value]) => (
          <div key={key}>
            <span className="text-text-muted">{key}: </span>
            <JsonTreeView data={value} depth={depth + 1} />
          </div>
        ))}
      </div>
      <span className="text-text-muted">{bracket[1]}</span>
    </span>
  )
}
