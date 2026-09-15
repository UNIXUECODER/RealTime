import { describe, it, expect } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { JsonTreeView } from './JsonTreeView'

describe('JsonTreeView', () => {
  it('renders primitive values with type-appropriate formatting', () => {
    render(<JsonTreeView data={{ name: 'test', count: 3, active: true, note: null }} />)
    expect(screen.getByText('"test"')).toBeInTheDocument()
    expect(screen.getByText('3')).toBeInTheDocument()
    expect(screen.getByText('true')).toBeInTheDocument()
    expect(screen.getByText('null')).toBeInTheDocument()
  })

  it('expands root-level entries but starts nested objects collapsed', () => {
    render(<JsonTreeView data={{ outer: { inner: 'value' } }} />)

    // Root (depth 0) is expanded — the key is visible immediately.
    expect(screen.getByText(/outer:/)).toBeInTheDocument()
    // Its nested object (depth 1) starts collapsed — the inner value isn't rendered yet.
    expect(screen.queryByText('"value"')).not.toBeInTheDocument()

    // Clicking the collapsed placeholder reveals it.
    fireEvent.click(screen.getByRole('button', { name: /\{…\}/ }))
    expect(screen.getByText('"value"')).toBeInTheDocument()
  })

  it('renders arrays using index-based entries', () => {
    // At root (depth 0) arrays start expanded, same as objects — this checks that
    // case specifically; a nested array's default-collapsed behavior is already
    // covered by the object-nesting test above.
    render(<JsonTreeView data={[10, 20]} />)
    expect(screen.getByText('10')).toBeInTheDocument()
    expect(screen.getByText('20')).toBeInTheDocument()
  })

  it('can collapse an expanded node back down', () => {
    render(<JsonTreeView data={{ a: 'visible' }} />)
    expect(screen.getByText('"visible"')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '{' }))
    expect(screen.queryByText('"visible"')).not.toBeInTheDocument()
  })
})
